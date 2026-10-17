package io.github.aedev.flow.data.youtubeaccount

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class YouTubeAccountRequestsTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `playlist edits carry the action names and ids InnerTube expects`() {
        val body =
            YouTubeAccountRequests.editPlaylist(
                "PLx",
                listOf(
                    PlaylistEdit.Add("vidAAAAAAAA"),
                    PlaylistEdit.Remove("vidBBBBBBBB"),
                    PlaylistEdit.Rename("New name"),
                    PlaylistEdit.MoveAfter(setVideoId = "SET1", predecessorSetVideoId = "SET2"),
                ),
            )

        assertThat(body).isEqualTo(
            json(
                """
                {"playlistId":"PLx","actions":[
                  {"action":"ACTION_ADD_VIDEO","addedVideoId":"vidAAAAAAAA","dedupeOption":"DEDUPE_OPTION_SKIP"},
                  {"action":"ACTION_REMOVE_VIDEO_BY_VIDEO_ID","removedVideoId":"vidBBBBBBBB"},
                  {"action":"ACTION_SET_PLAYLIST_NAME","playlistName":"New name"},
                  {"action":"ACTION_MOVE_VIDEO_AFTER","setVideoId":"SET1","movedSetVideoIdPredecessor":"SET2"}
                ]}
                """,
            ),
        )
    }

    @Test
    fun `targets, channels and feedback have the shapes the write endpoints read`() {
        assertThat(YouTubeAccountRequests.videoTarget("vid")).isEqualTo(json("""{"target":{"videoId":"vid"}}"""))
        assertThat(YouTubeAccountRequests.playlistTarget("PLx")).isEqualTo(json("""{"target":{"playlistId":"PLx"}}"""))
        assertThat(YouTubeAccountRequests.channels("UCx")).isEqualTo(json("""{"channelIds":["UCx"],"params":""}"""))
        assertThat(YouTubeAccountRequests.feedback(listOf("T1", "T2"))).isEqualTo(json("""{"feedbackTokens":["T1","T2"]}"""))
        assertThat(YouTubeAccountRequests.params("P")).isEqualTo(json("""{"params":"P"}"""))
    }

    @Test
    fun `a new playlist is private and only lists videos when given some`() {
        assertThat(YouTubeAccountRequests.createPlaylist("Mix", listOf("vid")))
            .isEqualTo(json("""{"title":"Mix","privacyStatus":"PRIVATE","videoIds":["vid"]}"""))
        assertThat(YouTubeAccountRequests.createPlaylist("Mix", emptyList()))
            .isEqualTo(json("""{"title":"Mix","privacyStatus":"PRIVATE"}"""))
    }

    @Test
    fun `the TV context carries the TV app info, the WEB one does not`() {
        val fields = YouTubeAccountRequests.playlistId("PLx")

        val tv = YouTubeAccountRequests.body(AccountClient.TV, "en", "US", fields)
        val web = YouTubeAccountRequests.body(AccountClient.WEB, "en", "US", fields)

        val tvClient = (tv["context"] as JsonObject)["client"] as JsonObject
        val webClient = (web["context"] as JsonObject)["client"] as JsonObject
        assertThat(tvClient["clientName"].toString()).isEqualTo("\"TVHTML5\"")
        assertThat(tvClient).containsKey("tvAppInfo")
        assertThat(webClient["clientName"].toString()).isEqualTo("\"WEB\"")
        assertThat(webClient).doesNotContainKey("tvAppInfo")
        assertThat(web["playlistId"].toString()).isEqualTo("\"PLx\"")
    }

    @Test
    fun `a player request carries the signature timestamp, without which YouTube leaves out the tracking URLs`() {
        assertThat(YouTubeAccountRequests.player("vid", signatureTimestamp = 20345)).isEqualTo(
            json(
                """
                {"videoId":"vid","racyCheckOk":true,"contentCheckOk":true,
                 "playbackContext":{"contentPlaybackContext":{"signatureTimestamp":20345}}}
                """,
            ),
        )
        assertThat(YouTubeAccountRequests.player("vid", signatureTimestamp = null))
            .isEqualTo(json("""{"videoId":"vid","racyCheckOk":true,"contentCheckOk":true}"""))
    }
}
