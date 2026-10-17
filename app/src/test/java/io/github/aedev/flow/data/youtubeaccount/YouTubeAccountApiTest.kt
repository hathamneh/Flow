package io.github.aedev.flow.data.youtubeaccount

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertThrows
import org.junit.Test

class YouTubeAccountApiTest {
    private val requests = mutableListOf<Request>()
    private val bodies = mutableListOf<String>()

    private fun api(reply: (Request) -> Pair<Int, String>): YouTubeAccountApi =
        YouTubeAccountApi(
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requests += request
                    bodies += request.body?.let { body -> Buffer().also(body::writeTo).readUtf8() }.orEmpty()
                    val (code, text) = reply(request)
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(code)
                        .message("")
                        .body(text.toResponseBody("application/json".toMediaType()))
                        .build()
                }.build(),
        )

    private val client = TvOAuthClient("id.apps.googleusercontent.com", "secret")

    @Test
    fun `reads the first OAuth client from the TV app bundle`() =
        runBlocking {
            val api =
                api { request ->
                    when {
                        request.url.encodedPath == "/tv" -> {
                            200 to """<script id="base-js" src="/s/_/kabuki/base.js"></script>"""
                        }

                        request.url.encodedPath.endsWith("base.js") -> {
                            200 to
                                """a={clientId:"111-tv.apps.googleusercontent.com",Sh:"TvSecret"};b={clientId:"222-other.apps.googleusercontent.com",x:"Other"}"""
                        }

                        else -> {
                            404 to ""
                        }
                    }
                }

            val found = api.fetchTvOAuthClient()

            assertThat(found).isEqualTo(TvOAuthClient("111-tv.apps.googleusercontent.com", "TvSecret"))
            assertThat(requests[1].url.toString()).isEqualTo("https://www.youtube.com/s/_/kabuki/base.js")
        }

    @Test
    fun `reads the OAuth client inline in the page when newer Cobalt builds get it there`() =
        runBlocking {
            val api =
                api { request ->
                    if (request.url.encodedPath == "/tv") {
                        200 to """<script nonce="n">sa={clientId:"333-tv.apps.googleusercontent.com",A:"Inline"},ta={}</script>"""
                    } else {
                        404 to ""
                    }
                }

            assertThat(api.fetchTvOAuthClient()).isEqualTo(TvOAuthClient("333-tv.apps.googleusercontent.com", "Inline"))
            assertThat(requests).hasSize(1)
        }

    @Test
    fun `token polling maps Google's pending and final answers`() =
        runBlocking {
            var answer = """{"error":"authorization_pending"}"""
            val api = api { 428 to answer }

            assertThat(api.pollDeviceToken(client, "dev")).isEqualTo(TokenPoll.Pending)
            answer = """{"error":"slow_down"}"""
            assertThat(api.pollDeviceToken(client, "dev")).isEqualTo(TokenPoll.SlowDown)
            answer = """{"error":"access_denied"}"""
            assertThat(api.pollDeviceToken(client, "dev")).isEqualTo(TokenPoll.Denied)
            answer = """{"access_token":"AT","expires_in":3600,"refresh_token":"RT"}"""
            val granted = api.pollDeviceToken(client, "dev") as TokenPoll.Granted
            assertThat(granted.tokens.accessToken).isEqualTo("AT")
            assertThat(granted.tokens.refreshToken).isEqualTo("RT")
            assertThat(bodies.last()).contains("\"grant_type\":\"http://oauth.net/grant_type/device/1.0\"")
        }

    @Test
    fun `a refused refresh token reads as revoked`() {
        val api = api { 400 to """{"error":"invalid_grant"}""" }
        assertThrows(YouTubeAuthRevokedException::class.java) {
            runBlocking { api.refreshAccessToken(client, "RT") }
        }
    }

    @Test
    fun `signed-in requests use the TV client with the bearer token and brand page id`() =
        runBlocking {
            val api = api { 200 to "{}" }

            api.browse(YouTubeAccountApi.Auth("AT", pageId = "PAGE"), browseId = "FEhistory")

            val request = requests.single()
            assertThat(request.url.toString()).startsWith("https://www.youtube.com/youtubei/v1/browse")
            assertThat(request.header("Authorization")).isEqualTo("Bearer AT")
            assertThat(request.header("X-Goog-Pageid")).isEqualTo("PAGE")
            assertThat(request.header("X-YouTube-Client-Name")).isEqualTo("7")
            assertThat(bodies.single()).contains("\"clientName\":\"TVHTML5\"")
            assertThat(bodies.single()).contains("\"browseId\":\"FEhistory\"")
        }

    @Test
    fun `stats pings add the session parameters to the tracking url`() =
        runBlocking {
            val api = api { 204 to "" }

            api.ping(
                YouTubeAccountApi.Auth("AT", pageId = null),
                "https://s.youtube.com/api/stats/playback?docid=abc&ei=E&ver=1",
                mapOf("ver" to "2", "cpn" to "CPN", "cmt" to "1.000"),
            )

            val url = requests.single().url
            assertThat(url.queryParameter("docid")).isEqualTo("abc")
            assertThat(url.queryParameterValues("ver")).containsExactly("2")
            assertThat(url.queryParameter("cpn")).isEqualTo("CPN")
            assertThat(requests.single().header("Authorization")).isEqualTo("Bearer AT")
        }

    @Test
    fun `a 401 from InnerTube asks for a token refresh rather than a new sign-in`() {
        val api = api { 401 to "{}" }
        assertThrows(YouTubeUnauthorizedException::class.java) {
            runBlocking { api.browse(YouTubeAccountApi.Auth("AT", null), browseId = "FEhistory") }
        }
    }
}
