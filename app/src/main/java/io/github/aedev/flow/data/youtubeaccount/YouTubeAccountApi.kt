package io.github.aedev.flow.data.youtubeaccount

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
import okhttp3.Response
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
                innerTube(
                    "account/accounts_list",
                    auth,
                    Requests.fields {
                        putJsonObject("accountReadMask") {
                            put("returnOwner", true)
                            put("returnBrandAccounts", true)
                            put("returnPersonaAccounts", false)
                        }
                    },
                ),
            )

        /** The playback-stats URLs for [videoId] as the signed-in account; streams in the response are ignored. */
        suspend fun trackingUrls(
            auth: Auth,
            videoId: String,
            signatureTimestamp: Int?,
        ): PlaybackTrackingUrls? = YouTubeAccountParsers.trackingUrls(player(auth, videoId, signatureTimestamp))

        /**
         * The signed-in `/player` response for [videoId], for its tracking URLs and playability; streams
         * are ignored. [signatureTimestamp] is the current player's (`PipePipeNsigDecoder`); without it
         * the response is UNPLAYABLE and has no tracking URLs.
         */
        suspend fun player(
            auth: Auth,
            videoId: String,
            signatureTimestamp: Int?,
        ): JsonElement = innerTube("player", auth, Requests.player(videoId, signatureTimestamp), device = true)

        /** `/player` asked as [variant], for the diagnostics to compare; the account calls use [player]. */
        suspend fun playerVariant(
            auth: Auth,
            variant: PlayerVariant,
            videoId: String,
            signatureTimestamp: Int?,
            visitorData: String?,
            poToken: String? = null,
        ): JsonElement =
            withContext(Dispatchers.IO) {
                val locale = Locale.getDefault()
                val body =
                    Requests.playerVariant(
                        variant,
                        hl = locale.language.ifBlank { "en" },
                        gl = locale.country.ifBlank { "US" },
                        videoId = videoId,
                        signatureTimestamp = signatureTimestamp,
                        visitorData = visitorData,
                        poToken = poToken,
                    )
                val request =
                    Request
                        .Builder()
                        .url("https://www.$YOUTUBE_HOST${INNERTUBE_PATH}player?prettyPrint=false")
                        .post(body.toString().toRequestBody(JSON_TYPE))
                        .header("X-YouTube-Client-Name", variant.client.clientId)
                        .header("X-YouTube-Client-Version", variant.client.clientVersion)
                        .header("Origin", "https://www.$YOUTUBE_HOST")
                        .header("Referer", variant.referer)
                        .apply {
                            if (variant.signedIn) authHeaders(auth, variant.userAgent) else header("User-Agent", variant.userAgent)
                            if (variant.sendsVisitor && visitorData != null) header("X-Goog-Visitor-Id", visitorData)
                        }.build()
                httpClient.newCall(request).execute().use { response ->
                    response.throwIfFailed("InnerTube player")
                    json.parseToJsonElement(response.body.string())
                }
            }

        suspend fun browse(
            auth: Auth,
            browseId: String? = null,
            continuation: String? = null,
            client: AccountClient = AccountClient.TV,
        ): JsonElement =
            innerTube(
                "browse",
                auth,
                Requests.fields {
                    browseId?.let { put("browseId", it) }
                    continuation?.let { put("continuation", it) }
                },
                client,
            )

        /** The watch page for [videoId] as the account sees it: its like, subscribe and bell state. */
        suspend fun next(
            auth: Auth,
            videoId: String,
        ): JsonElement = innerTube("next", auth, Requests.videoId(videoId))

        suspend fun rate(
            auth: Auth,
            videoId: String,
            action: LikeAction,
        ) {
            innerTube(action.path, auth, Requests.videoTarget(videoId))
        }

        /** Saves someone else's playlist to the library, or removes it. */
        suspend fun setPlaylistSaved(
            auth: Auth,
            playlistId: String,
            saved: Boolean,
        ) {
            innerTube(if (saved) LikeAction.LIKE.path else LikeAction.REMOVE.path, auth, Requests.playlistTarget(playlistId))
        }

        /** [params] on a subscribe carry a bell choice, the way the TV app re-subscribes to change it. */
        suspend fun setSubscribed(
            auth: Auth,
            channelId: String,
            subscribed: Boolean,
            params: String = "",
        ) {
            innerTube(if (subscribed) "subscription/subscribe" else "subscription/unsubscribe", auth, Requests.channels(channelId, params))
        }

        /** Applies [edits] in order to a playlist; `WL` is Watch later. */
        suspend fun editPlaylist(
            auth: Auth,
            playlistId: String,
            edits: List<PlaylistEdit>,
        ) {
            innerTube("browse/edit_playlist", auth, Requests.editPlaylist(playlistId, edits))
        }

        /** The new playlist's id, or null when YouTube accepted the call without returning one. */
        suspend fun createPlaylist(
            auth: Auth,
            title: String,
            videoIds: List<String> = emptyList(),
            client: AccountClient = AccountClient.TV,
            private: Boolean = true,
        ): String? =
            YouTubeAccountParsers.createdPlaylistId(
                innerTube("playlist/create", auth, Requests.createPlaylist(title, videoIds, private), client),
            )

        suspend fun deletePlaylist(
            auth: Auth,
            playlistId: String,
            client: AccountClient = AccountClient.TV,
        ) {
            innerTube("playlist/delete", auth, Requests.playlistId(playlistId), client)
        }

        /** Sends feedback tokens read from a signed-in feed, such as "remove from watch history". */
        suspend fun feedback(
            auth: Auth,
            tokens: List<String>,
        ) {
            innerTube("feedback", auth, Requests.feedback(tokens))
        }

        suspend fun setWatchHistoryPaused(
            auth: Auth,
            paused: Boolean,
        ) {
            innerTube(if (paused) "history/pause_watch_history" else "history/resume_watch_history", auth, Requests.fields {})
        }

        suspend fun clearWatchHistory(auth: Auth) {
            innerTube("history/clear_watch_history", auth, Requests.fields {})
        }

        /** Sets a channel's bell; [params] come from that state's option in [next]. */
        suspend fun setNotificationPreference(
            auth: Auth,
            params: String,
        ) {
            innerTube("notification/modify_channel_preference", auth, Requests.params(params))
        }

        /** The "remove from watch history" token of [videoId], looked up on the first history pages. */
        suspend fun historyFeedbackToken(
            auth: Auth,
            videoId: String,
        ): String? {
            var token: String? = null
            browsePages(auth, HISTORY, HISTORY_TOKEN_PAGES) { page ->
                token = YouTubeAccountParsers.historyFeedbackTokens(page)[videoId]
                token == null
            }
            return token
        }

        /** Fires one playback-stats ping (`/api/stats/playback` or `/api/stats/watchtime`) and returns its HTTP status. */
        suspend fun ping(
            auth: Auth,
            baseUrl: String,
            params: Map<String, String>,
        ): Int =
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
                    response.throwIfFailed("Stats ping")
                    response.code
                }
            }

        private suspend fun innerTube(
            path: String,
            auth: Auth,
            fields: JsonObject,
            client: AccountClient = AccountClient.TV,
            device: Boolean = false,
        ): JsonElement =
            withContext(Dispatchers.IO) {
                val locale = Locale.getDefault()
                val body =
                    Requests.body(
                        client,
                        hl = locale.language.ifBlank { "en" },
                        gl = locale.country.ifBlank { "US" },
                        fields = fields,
                        device = device,
                    )
                val request =
                    Request
                        .Builder()
                        .url("${client.host}$INNERTUBE_PATH$path?prettyPrint=false")
                        .post(body.toString().toRequestBody(JSON_TYPE))
                        .header("X-YouTube-Client-Name", client.client.clientId)
                        .header("X-YouTube-Client-Version", client.client.clientVersion)
                        .header("Origin", client.host)
                        .header("Referer", client.referer)
                        .authHeaders(auth, client.userAgent)
                        .build()
                httpClient.newCall(request).execute().use { response ->
                    response.throwIfFailed("InnerTube $path")
                    val text = response.body.string()
                    if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text)
                }
            }

        private fun Response.throwIfFailed(what: String) {
            if (code == HTTP_UNAUTHORIZED) throw YouTubeUnauthorizedException()
            if (!isSuccessful) throw YouTubeHttpException(code, "$what failed: HTTP $code")
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

        private fun Request.Builder.authHeaders(
            auth: Auth,
            userAgent: String = TV_USER_AGENT,
        ): Request.Builder =
            apply {
                header("Authorization", "Bearer ${auth.accessToken}")
                header("User-Agent", userAgent)
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
            val Requests = YouTubeAccountRequests
            const val TV_PAGE_URL = "https://www.youtube.com/tv"
            const val INNERTUBE_PATH = "/youtubei/v1/"
            const val HISTORY = "FEhistory"
            const val HISTORY_TOKEN_PAGES = 3
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

/** YouTube answered with an HTTP error; [code] is what callers classify on (retry, give up, refresh). */
open class YouTubeHttpException(
    val code: Int,
    message: String,
) : IOException(message)

/** InnerTube answered 401: the cached access token expired early or was revoked. */
class YouTubeUnauthorizedException : YouTubeHttpException(401, "YouTube refused the access token")
