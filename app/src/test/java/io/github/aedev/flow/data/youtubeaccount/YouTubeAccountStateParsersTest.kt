package io.github.aedev.flow.data.youtubeaccount

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/** The readers the two-way sync and its diagnostics use: watch-page state, history tokens, playlist entries. */
class YouTubeAccountStateParsersTest {
    private fun parse(text: String) = Json.parseToJsonElement(text)

    private val watchPage =
        parse(
            """
            {"contents":{"singleColumnWatchNextResults":{"results":{"results":{"contents":[
              {"itemSectionRenderer":{"contents":[{"videoMetadataRenderer":{
                "videoId":"vidAAAAAAAA",
                "likeButton":{"likeButtonRenderer":{"target":{"videoId":"vidAAAAAAAA"},"likeStatus":"LIKE"}},
                "owner":{"videoOwnerRenderer":{"subscriptionButton":{"subscribeButtonRenderer":{
                  "subscribed":true,"channelId":"UCowner",
                  "notificationPreferenceButton":{"subscriptionNotificationToggleButtonRenderer":{
                    "states":[
                      {"stateId":2,"state":{"buttonRenderer":{"icon":{"iconType":"NOTIFICATIONS_ACTIVE"}}},
                       "inlineMenuButton":{"buttonRenderer":{"serviceEndpoint":{
                         "modifyChannelNotificationPreferenceEndpoint":{"params":"P-ALL%3D%3D"}}}},
                       "notificationState":"SUBSCRIPTION_NOTIFICATION_STATE_ALL"},
                      {"stateId":0,"state":{"buttonRenderer":{"icon":{"iconType":"NOTIFICATIONS_OFF"}}},
                       "inlineMenuButton":{"buttonRenderer":{"serviceEndpoint":{
                         "modifyChannelNotificationPreferenceEndpoint":{"params":"P-OFF"}}}},
                       "notificationState":"SUBSCRIPTION_NOTIFICATION_STATE_OFF"}
                    ],
                    "currentStateId":0}}}}}}
              }}]}},
              {"compactVideoRenderer":{"likeButton":{"likeButtonRenderer":{"target":{"playlistId":"PLx"},"likeStatus":"INDIFFERENT"}},
                "subscribeButton":{"subscribeButtonRenderer":{"subscribed":false,"channelId":"UCother"}}}}
            ]}}}}}
            """,
        )

    @Test
    fun `like status is read from the button that targets the video`() {
        assertThat(YouTubeAccountParsers.likeStatus(watchPage, "vidAAAAAAAA")).isEqualTo("LIKE")
        assertThat(YouTubeAccountParsers.likeStatus(watchPage, "vidMISSING0")).isNull()
    }

    @Test
    fun `subscribed is read per channel, and unknown when the page has no button for it`() {
        assertThat(YouTubeAccountParsers.isSubscribed(watchPage, "UCowner")).isTrue()
        assertThat(YouTubeAccountParsers.isSubscribed(watchPage, "UCother")).isFalse()
        assertThat(YouTubeAccountParsers.isSubscribed(watchPage, "UCnobody")).isNull()
    }

    @Test
    fun `bell options come from the channel's toggle states, decoded, with the current one selected`() {
        val options = YouTubeAccountParsers.bellOptions(watchPage, "UCowner")

        assertThat(options)
            .containsExactly(
                BellOption("SUBSCRIPTION_NOTIFICATION_STATE_ALL", "P-ALL==", selected = false),
                BellOption("SUBSCRIPTION_NOTIFICATION_STATE_OFF", "P-OFF", selected = true),
            ).inOrder()
    }

    @Test
    fun `bell options also read the newer list-item shape`() {
        val root =
            parse(
                """
                {"listViewModel":{"listItems":[
                  {"listItemViewModel":{"title":{"content":"All"},"isSelected":true,
                    "leadingImage":{"sources":[{"clientResource":{"imageName":"NOTIFICATIONS_ACTIVE"}}]},
                    "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{
                      "modifyChannelNotificationPreferenceEndpoint":{"params":"A"}}}}}}},
                  {"listItemViewModel":{"title":{"content":"None"},"isSelected":false,
                    "leadingImage":{"sources":[{"clientResource":{"imageName":"NOTIFICATIONS_OFF"}}]},
                    "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{
                      "modifyChannelNotificationPreferenceEndpoint":{"params":"N"}}}}}}},
                  {"listItemViewModel":{"title":{"content":"Unrelated"}}}
                ]}}
                """,
            )

        assertThat(YouTubeAccountParsers.bellOptions(root, "UCany"))
            .containsExactly(BellOption("NOTIFICATIONS_ACTIVE", "A", true), BellOption("NOTIFICATIONS_OFF", "N", false))
            .inOrder()
    }

    @Test
    fun `history feedback tokens are keyed by the tile's video`() {
        val root =
            parse(
                """
                {"contents":[
                  {"tileRenderer":{"contentId":"vidAAAAAAAA","contentType":"TILE_CONTENT_TYPE_VIDEO",
                    "onLongPressCommand":{"showMenuCommand":{"menu":{"menuRenderer":{"items":[
                      {"menuServiceItemRenderer":{"serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"TOKEN-A"}}}}]}}}}}},
                  {"tileRenderer":{"onSelectCommand":{"watchEndpoint":{"videoId":"vidBBBBBBBB"}},
                    "menu":{"menuRenderer":{"items":[
                      {"menuServiceItemRenderer":{"serviceEndpoint":{"feedbackEndpoint":{"feedbackToken":"TOKEN-B"}}}}]}}}},
                  {"tileRenderer":{"contentId":"vidCCCCCCCC"}}
                ]}
                """,
            )

        assertThat(YouTubeAccountParsers.historyFeedbackTokens(root))
            .containsExactly("vidAAAAAAAA", "TOKEN-A", "vidBBBBBBBB", "TOKEN-B")
    }

    @Test
    fun `playlist entries keep order and duplicates and read either setVideoId key`() {
        val root =
            parse(
                """
                {"contents":[
                  {"tileRenderer":{"contentId":"vidAAAAAAAA",
                    "menu":{"menuRenderer":{"items":[{"menuServiceItemRenderer":{"serviceEndpoint":{"playlistEditEndpoint":{
                      "actions":[{"action":"ACTION_REMOVE_VIDEO","setVideoId":"SET-1"}]}}}}]}}}},
                  {"tileRenderer":{"contentId":"vidBBBBBBBB","playlistSetVideoId":"SET-2"}},
                  {"tileRenderer":{"contentId":"vidAAAAAAAA","setVideoId":"SET-3"}},
                  {"playlistVideoRenderer":{"videoId":"vidCCCCCCCC","setVideoId":"SET-4"}},
                  {"tileRenderer":{"contentId":"vidDDDDDDDD"}}
                ]}
                """,
            )

        assertThat(YouTubeAccountParsers.playlistEntries(root))
            .containsExactly(
                PlaylistEntry("vidAAAAAAAA", "SET-1"),
                PlaylistEntry("vidBBBBBBBB", "SET-2"),
                PlaylistEntry("vidAAAAAAAA", "SET-3"),
                PlaylistEntry("vidCCCCCCCC", "SET-4"),
                PlaylistEntry("vidDDDDDDDD", null),
            ).inOrder()
    }

    @Test
    fun `created playlist id is read from the create response`() {
        assertThat(YouTubeAccountParsers.createdPlaylistId(parse("""{"responseContext":{},"playlistId":"PLnew"}"""))).isEqualTo("PLnew")
        assertThat(YouTubeAccountParsers.createdPlaylistId(parse("""{"responseContext":{}}"""))).isNull()
    }

    @Test
    fun `playability reads the status and its reason`() {
        val refused = parse("""{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":{"simpleText":"Sign in to confirm"}}}""")
        assertThat(YouTubeAccountParsers.playability(refused)).isEqualTo("LOGIN_REQUIRED: Sign in to confirm")
        assertThat(YouTubeAccountParsers.playability(parse("""{"playabilityStatus":{"status":"OK"}}"""))).isEqualTo("OK")
        assertThat(YouTubeAccountParsers.playability(parse("{}"))).isNull()
    }

    @Test
    fun `a playlist is the account's own when it offers edits but no save button`() {
        val edit =
            """{"menuServiceItemRenderer":{"serviceEndpoint":{"playlistEditEndpoint":{"playlistId":"PLx",
               "actions":[{"action":"ACTION_REMOVE_VIDEO","setVideoId":"SET-1"}]}}}}"""
        val save =
            """{"toggleButtonRenderer":{"defaultServiceEndpoint":
               {"likeEndpoint":{"status":"LIKE","target":{"playlistId":"PLx"}}}}}"""
        val own = parse("""{"contents":[{"tileRenderer":{"contentId":"vidAAAAAAAA","menu":{"menuRenderer":{"items":[$edit]}}}}]}""")
        val saved =
            parse(
                """{"header":$save,"contents":[{"tileRenderer":{"contentId":"vidAAAAAAAA","menu":{"menuRenderer":{"items":[$edit]}}}}]}""",
            )
        val public = parse("""{"header":$save,"contents":[{"tileRenderer":{"contentId":"vidAAAAAAAA"}}]}""")

        assertThat(YouTubeAccountParsers.isOwnPlaylist(own)).isTrue()
        assertThat(YouTubeAccountParsers.isOwnPlaylist(saved)).isFalse()
        assertThat(YouTubeAccountParsers.isOwnPlaylist(public)).isFalse()
    }
}
