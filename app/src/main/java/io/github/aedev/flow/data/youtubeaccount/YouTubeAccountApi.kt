package io.github.aedev.flow.data.youtubeaccount

import io.github.aedev.flow.innertube.models.YouTubeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every network call the signed-in features make, and nothing else: the TV device-code sign-in, and
 * TV-client InnerTube requests carrying the account's bearer token. Playback never goes through here —
 * streams stay on Flow's anonymous extraction path, so signing in cannot change what plays or bring
 * ads into it.
 *
 * A token minted for the TV app's OAuth client is only honoured by TV-client requests, which is why
 * everything here speaks `TVHTML5`.
 */
@Singleton
class YouTubeAccountApi
    @Inject
    constructor(
        private val httpClient: OkHttpClient,
    ) {
        private val json = Json { ignoreUnknownKeys = true }

        /** Reads the TV app's OAuth client from the bundle youtube.com/tv loads, so it follows Google's rotations. */
        suspend fun fetchTvOAuthClient(): TvOAuthClient? =
            withContext(Dispatchers.IO) {
                val page = get(TV_PAGE_URL) ?: return@withContext null
                // Newer Cobalt builds get the client inline in the page; others in the base script it loads.
                OAUTH_CLIENT.find(page)?.let { return@withContext it.toClient() }
                val pageUrl = TV_PAGE_URL.toHttpUrl()
                val scripts =
                    (BASE_JS.findAll(page) + SCRIPT_SRC.findAll(page))
                        .map { it.groupValues[1] }
                        .distinct()
                        .mapNotNull { pageUrl.resolve(it)?.takeIf { url -> url.host.endsWith(YOUTUBE_HOST) } }
                        .take(MAX_SCRIPTS)
                scripts.firstNotNullOfOrNull { url -> get(url.toString())?.let(OAUTH_CLIENT::find) }?.toClient()
            }

        suspend fun requestDeviceCode(
            client: TvOAuthClient,
            deviceId: String,
        ): DeviceCode =
            withContext(Dispatchers.IO) {
                val body =
                    buildJsonObject {
                        put("client_id", client.clientId)
                        put("device_id", deviceId)
                        put("model_name", MODEL_NAME)
                        put("scope", SCOPE)
                    }
                val response = postJson(DEVICE_CODE_URL, body) as JsonObject
                DeviceCode(
                    deviceCode = response.string("device_code") ?: throw IOException("No device code"),
                    userCode = response.string("user_code") ?: throw IOException("No user code"),
                    verificationUrl = response.string("verification_url") ?: DEFAULT_VERIFICATION_URL,
                    expiresAtMs = System.currentTimeMillis() + (response.long("expires_in") ?: DEFAULT_EXPIRY_S) * 1000,
                    intervalSeconds = (response.long("interval") ?: DEFAULT_INTERVAL_S).toInt(),
                )
            }

        suspend fun pollDeviceToken(
            client: TvOAuthClient,
            deviceCode: String,
        ): TokenPoll =
            withContext(Dispatchers.IO) {
                val body =
                    buildJsonObject {
                        put("client_id", client.clientId)
                        put("client_secret", client.clientSecret)
                        put("code", deviceCode)
                        put("grant_type", DEVICE_GRANT)
                    }
                val response = postJson(TOKEN_URL, body, allowErrors = true) as JsonObject
                when (response.string("error")) {
                    null -> TokenPoll.Granted(response.toTokens())
                    "authorization_pending" -> TokenPoll.Pending
                    "slow_down" -> TokenPoll.SlowDown
                    "access_denied" -> TokenPoll.Denied
                    "expired_token" -> TokenPoll.Expired
                    else -> throw IOException("Sign-in failed: ${response.string("error")}")
                }
            }

        /** A fresh access token; [YouTubeAuthRevokedException] when Google no longer accepts [refreshToken]. */
        suspend fun refreshAccessToken(
            client: TvOAuthClient,
            refreshToken: String,
        ): OAuthTokens =
            withContext(Dispatchers.IO) {
                val body =
                    buildJsonObject {
                        put("client_id", client.clientId)
                        put("client_secret", client.clientSecret)
                        put("refresh_token", refreshToken)
                        put("grant_type", "refresh_token")
                    }
                val response = postJson(TOKEN_URL, body, allowErrors = true) as JsonObject
                when (response.string("error")) {
                    null -> response.toTokens()
                    "invalid_grant", "unauthorized_client" -> throw YouTubeAuthRevokedException()
                    else -> throw IOException("Token refresh failed: ${response.string("error")}")
                }
            }

        /** Best effort: tells Google to forget the sign-in when an account is removed from Flow. */
        suspend fun revoke(token: String) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val url =
                        REVOKE_URL
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("token", token)
                            .build()
                    httpClient
                        .newCall(
                            Request
                                .Builder()
                                .url(url)
                                .post(ByteArray(0).toRequestBody())
                                .build(),
                        ).execute()
                        .close()
                }
            }
        }

        suspend fun accounts(auth: Auth): List<RemoteIdentity> =
            YouTubeAccountParsers.accounts(
                innerTube("account/accounts_list", auth) {
                    putJsonObject("accountReadMask") {
                        put("returnOwner", true)
                        put("returnBrandAccounts", true)
                        put("returnPersonaAccounts", false)
                    }
                },
            )

        /** The playback-stats URLs for [videoId] as the signed-in account; streams in the response are ignored. */
        suspend fun trackingUrls(
            auth: Auth,
            videoId: String,
        ): PlaybackTrackingUrls? =
            YouTubeAccountParsers.trackingUrls(
                innerTube("player", auth) {
                    put("videoId", videoId)
                    put("racyCheckOk", true)
                    put("contentCheckOk", true)
                },
            )

        suspend fun browse(
            auth: Auth,
            browseId: String? = null,
            continuation: String? = null,
        ): JsonElement =
            innerTube("browse", auth) {
                browseId?.let { put("browseId", it) }
                continuation?.let { put("continuation", it) }
            }

        /** Fires one playback-stats ping (`/api/stats/playback` or `/api/stats/watchtime`). */
        suspend fun ping(
            auth: Auth,
            baseUrl: String,
            params: Map<String, String>,
        ) {
            withContext(Dispatchers.IO) {
                val url =
                    baseUrl
                        .toHttpUrl()
                        .newBuilder()
                        .apply { params.forEach { (key, value) -> setQueryParameter(key, value) } }
                        .build()
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .authHeaders(auth)
                        .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.code == HTTP_UNAUTHORIZED) throw YouTubeUnauthorizedException()
                    if (!response.isSuccessful) throw IOException("Stats ping failed: HTTP ${response.code}")
                }
            }
        }

        private suspend fun innerTube(
            path: String,
            auth: Auth,
            fields: JsonObjectBuilder.() -> Unit,
        ): JsonElement =
            withContext(Dispatchers.IO) {
                val locale = Locale.getDefault()
                val body =
                    buildJsonObject {
                        putJsonObject("context") {
                            putJsonObject("client") {
                                put("clientName", TV_CLIENT.clientName)
                                put("clientVersion", TV_CLIENT.clientVersion)
                                put("hl", locale.language.ifBlank { "en" })
                                put("gl", locale.country.ifBlank { "US" })
                                put("userAgent", TV_USER_AGENT)
                                putJsonObject("tvAppInfo") {
                                    put("appQuality", "TV_APP_QUALITY_FULL_ANIMATION")
                                    put("zylonLeftNav", true)
                                }
                            }
                            putJsonObject("user") { put("enableSafetyMode", false) }
                        }
                        fields()
                    }
                val request =
                    Request
                        .Builder()
                        .url("$INNERTUBE_URL$path?prettyPrint=false")
                        .post(body.toString().toRequestBody(JSON_TYPE))
                        .header("X-YouTube-Client-Name", TV_CLIENT.clientId)
                        .header("X-YouTube-Client-Version", TV_CLIENT.clientVersion)
                        .header("Origin", ORIGIN)
                        .header("Referer", TV_PAGE_URL)
                        .authHeaders(auth)
                        .build()
                httpClient.newCall(request).execute().use { response ->
                    if (response.code == HTTP_UNAUTHORIZED) throw YouTubeUnauthorizedException()
                    if (!response.isSuccessful) throw IOException("InnerTube $path failed: HTTP ${response.code}")
                    json.parseToJsonElement(response.body.string())
                }
            }

        private fun get(url: String): String? =
            httpClient
                .newCall(
                    Request
                        .Builder()
                        .url(url)
                        .header("User-Agent", TV_USER_AGENT)
                        .build(),
                ).execute()
                .use { response -> if (response.isSuccessful) response.body.string() else null }

        private fun postJson(
            url: String,
            body: JsonObject,
            allowErrors: Boolean = false,
        ): JsonElement {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .post(body.toString().toRequestBody(JSON_TYPE))
                    .header("User-Agent", TV_USER_AGENT)
                    .build()
            return httpClient.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful && !(allowErrors && text.isNotBlank())) {
                    throw IOException("$url failed: HTTP ${response.code}")
                }
                json.parseToJsonElement(text)
            }
        }

        private fun Request.Builder.authHeaders(auth: Auth): Request.Builder =
            apply {
                header("Authorization", "Bearer ${auth.accessToken}")
                header("User-Agent", TV_USER_AGENT)
                auth.pageId?.let { header("X-Goog-Pageid", it) }
            }

        private fun JsonObject.toTokens(): OAuthTokens =
            OAuthTokens(
                accessToken = string("access_token") ?: throw IOException("No access token"),
                expiresAtMs = System.currentTimeMillis() + (long("expires_in") ?: DEFAULT_TOKEN_LIFETIME_S) * 1000,
                refreshToken = string("refresh_token"),
            )

        private fun MatchResult.toClient() = TvOAuthClient(clientId = groupValues[1], clientSecret = groupValues[2])

        private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

        /** The bearer token for one request, plus the brand-channel page id when acting as one. */
        data class Auth(
            val accessToken: String,
            val pageId: String?,
        )

        private companion object {
            val TV_CLIENT = YouTubeClient.TVHTML5
            const val TV_USER_AGENT =
                "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), " +
                    "Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"
            const val ORIGIN = "https://www.youtube.com"
            const val TV_PAGE_URL = "https://www.youtube.com/tv"
            const val INNERTUBE_URL = "https://www.youtube.com/youtubei/v1/"
            const val DEVICE_CODE_URL = "https://www.youtube.com/o/oauth2/device/code"
            const val TOKEN_URL = "https://www.youtube.com/o/oauth2/token"
            const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
            const val DEFAULT_VERIFICATION_URL = "https://www.google.com/device"
            const val SCOPE = "http://gdata.youtube.com https://www.googleapis.com/auth/youtube-paid-content"
            const val MODEL_NAME = "ytlr::"
            const val DEVICE_GRANT = "http://oauth.net/grant_type/device/1.0"
            const val DEFAULT_EXPIRY_S = 1800L
            const val DEFAULT_INTERVAL_S = 5L
            const val DEFAULT_TOKEN_LIFETIME_S = 3600L
            const val HTTP_UNAUTHORIZED = 401
            val JSON_TYPE = "application/json".toMediaType()
            const val YOUTUBE_HOST = "youtube.com"
            const val MAX_SCRIPTS = 6
            val BASE_JS = Regex("""id="base-js"\s+src="([^"]+)"""")
            val SCRIPT_SRC = Regex("""<script[^>]*\ssrc="([^"]+\.js[^"]*)"""")

            // The first client in the bundle is the TV app's own; later ones belong to other surfaces.
            val OAUTH_CLIENT = Regex("""clientId:"([-\w]+\.apps\.googleusercontent\.com)",\n?[$\w]+:"(\w+)"""")
        }
    }

/** InnerTube answered 401: the cached access token expired early or was revoked. */
class YouTubeUnauthorizedException : IOException("YouTube refused the access token")
