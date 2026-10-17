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

    private val auth = YouTubeAccountApi.Auth("AT", pageId = null)

    @Test
    fun `writes go to their endpoint with the TV client and the target in the body`() =
        runBlocking {
            val api = api { 200 to "{}" }

            api.rate(auth, "vidAAAAAAAA", LikeAction.REMOVE)
            api.setSubscribed(auth, "UCx", subscribed = false)
            api.editPlaylist(auth, "WL", listOf(PlaylistEdit.Add("vidAAAAAAAA")))
            api.setWatchHistoryPaused(auth, paused = true)

            assertThat(requests.map { it.url.encodedPath })
                .containsExactly(
                    "/youtubei/v1/like/removelike",
                    "/youtubei/v1/subscription/unsubscribe",
                    "/youtubei/v1/browse/edit_playlist",
                    "/youtubei/v1/history/pause_watch_history",
                ).inOrder()
            assertThat(requests.map { it.header("X-YouTube-Client-Name") }.distinct()).containsExactly("7")
            assertThat(bodies[0]).contains("\"target\":{\"videoId\":\"vidAAAAAAAA\"}")
            assertThat(bodies[1]).contains("\"channelIds\":[\"UCx\"]")
            assertThat(bodies[2]).contains("\"dedupeOption\":\"DEDUPE_OPTION_SKIP\"")
        }

    @Test
    fun `a playlist can be created with the WEB body and returns its id`() =
        runBlocking {
            val api = api { 200 to """{"playlistId":"PLnew"}""" }

            val id = api.createPlaylist(auth, "Probe", listOf("vidAAAAAAAA"), AccountClient.WEB)

            assertThat(id).isEqualTo("PLnew")
            assertThat(requests.single().url.encodedPath).isEqualTo("/youtubei/v1/playlist/create")
            assertThat(requests.single().header("X-YouTube-Client-Name")).isEqualTo("1")
            assertThat(requests.single().header("Authorization")).isEqualTo("Bearer AT")
            assertThat(bodies.single()).contains("\"clientName\":\"WEB\"")
        }

    @Test
    fun `YouTube Music requests go to the music host`() =
        runBlocking {
            val api = api { 200 to "{}" }

            api.browse(auth, browseId = "FEmusic_liked_videos", client = AccountClient.WEB_REMIX)

            assertThat(requests.single().url.host).isEqualTo("music.youtube.com")
            assertThat(requests.single().header("Origin")).isEqualTo("https://music.youtube.com")
            assertThat(bodies.single()).contains("\"clientName\":\"WEB_REMIX\"")
        }

    @Test
    fun `a refused write carries its HTTP status`() {
        val api = api { 400 to """{"error":{"code":400}}""" }
        val error =
            assertThrows(YouTubeHttpException::class.java) {
                runBlocking { api.deletePlaylist(auth, "PLx") }
            }
        assertThat(error.code).isEqualTo(400)
    }

    @Test
    fun `an empty write response is accepted`() =
        runBlocking {
            api { 200 to "" }.clearWatchHistory(auth)
            assertThat(requests.single().url.encodedPath).isEqualTo("/youtubei/v1/history/clear_watch_history")
        }

    @Test
    fun `the history token lookup pages until the video turns up`() =
        runBlocking {
            val api =
                api { request ->
                    val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                    if ("\"continuation\"" in body) {
                        200 to
                            """{"tileRenderer":{"contentId":"vidBBBBBBBB",
                              "menu":{"feedbackEndpoint":{"feedbackToken":"TOKEN-B"}}}}"""
                    } else {
                        200 to
                            """{"contents":[{"tileRenderer":{"contentId":"vidAAAAAAAA",
                              "menu":{"feedbackEndpoint":{"feedbackToken":"TOKEN-A"}}}}],
                              "continuations":[{"nextContinuationData":{"continuation":"NEXT"}}]}"""
                    }
                }

            assertThat(api.historyFeedbackToken(auth, "vidBBBBBBBB")).isEqualTo("TOKEN-B")
            assertThat(requests).hasSize(2)
        }

    private fun pagedApi(pages: Int) =
        api { request ->
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val page =
                Regex("\"continuation\":\"P(\\d+)\"")
                    .find(body)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt() ?: 1
            val next = if (page < pages) ""","continuations":[{"nextContinuationData":{"continuation":"P${page + 1}"}}]""" else ""
            200 to """{"contents":[{"tileRenderer":{"contentId":"vid${page}AAAAAAA"}}]$next}"""
        }

    @Test
    fun `a walk that reaches the last page says it saw the whole list`() =
        runBlocking {
            assertThat(pagedApi(pages = 2).browsePages(auth, "VLWL", maxPages = 5) { true }).isTrue()
            assertThat(requests).hasSize(2)
        }

    @Test
    fun `a walk cut short by its page cap or its caller did not see the whole list`() =
        runBlocking {
            assertThat(pagedApi(pages = 4).browsePages(auth, "VLWL", maxPages = 2) { true }).isFalse()
            assertThat(pagedApi(pages = 4).browsePages(auth, "VLWL", maxPages = 5) { false }).isFalse()
            // A single page with nothing after it is the whole list, even when the caller would stop there.
            assertThat(pagedApi(pages = 1).browsePages(auth, "VLWL", maxPages = 5) { false }).isTrue()
        }

    @Test
    fun `the account player call asks as the Tizen TV client with the signature timestamp`() =
        runBlocking {
            api { 200 to "{}" }.player(auth, "vid", signatureTimestamp = 20739)

            val body = bodies.single()
            assertThat(requests.single().url.encodedPath).isEqualTo("/youtubei/v1/player")
            assertThat(requests.single().header("Authorization")).isEqualTo("Bearer AT")
            assertThat(body).contains("\"osName\":\"Tizen\"")
            assertThat(body).contains("\"clientVersion\":\"5.")
            assertThat(body).contains("\"signatureTimestamp\":20739")
        }
}
