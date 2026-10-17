package io.github.aedev.flow.data.youtubeaccount

import io.github.aedev.flow.innertube.models.YouTubeClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The InnerTube client a signed-in request speaks as. The TV token is only honoured by [TV]; the
 * other two exist so the sync diagnostics can show what Google does with them today.
 */
enum class AccountClient(
    internal val client: YouTubeClient,
    internal val host: String,
    internal val referer: String,
    internal val userAgent: String,
) {
    TV(YouTubeClient.TVHTML5, YOUTUBE, "$YOUTUBE/tv", TV_USER_AGENT),
    WEB(YouTubeClient.WEB, YOUTUBE, "$YOUTUBE/", YouTubeClient.USER_AGENT_WEB),
    WEB_REMIX(YouTubeClient.WEB_REMIX, MUSIC, "$MUSIC/", YouTubeClient.USER_AGENT_WEB),
}

/** The rating endpoints. [REMOVE] clears a like or a dislike; there is no separate `removedislike` (HTTP 404). */
enum class LikeAction(
    internal val path: String,
) {
    LIKE("like/like"),
    DISLIKE("like/dislike"),
    REMOVE("like/removelike"),
}

/** One change to a playlist through `browse/edit_playlist`. */
sealed interface PlaylistEdit {
    /** Skips the add when the video is already in the playlist, since a plain add duplicates it. */
    data class Add(
        val videoId: String,
    ) : PlaylistEdit

    data class Remove(
        val videoId: String,
    ) : PlaylistEdit

    data class Rename(
        val name: String,
    ) : PlaylistEdit

    /** [setVideoId] identifies one entry of the playlist; it is read from a `VL<id>` browse. */
    data class MoveAfter(
        val setVideoId: String,
        val predecessorSetVideoId: String,
    ) : PlaylistEdit
}

internal const val TV_USER_AGENT =
    "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), " +
        "Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"
private const val YOUTUBE = "https://www.youtube.com"
private const val MUSIC = "https://music.youtube.com"

/** Pure builders for the signed-in request bodies, kept apart from the HTTP so they can be tested. */
internal object YouTubeAccountRequests {
    fun body(
        client: AccountClient,
        hl: String,
        gl: String,
        fields: JsonObject,
    ): JsonObject =
        buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", client.client.clientName)
                    put("clientVersion", client.client.clientVersion)
                    put("hl", hl)
                    put("gl", gl)
                    if (client == AccountClient.TV) {
                        put("userAgent", TV_USER_AGENT)
                        putJsonObject("tvAppInfo") {
                            put("appQuality", "TV_APP_QUALITY_FULL_ANIMATION")
                            put("zylonLeftNav", true)
                        }
                    }
                }
                putJsonObject("user") { put("enableSafetyMode", false) }
            }
            fields.forEach { (key, value) -> put(key, value) }
        }

    fun fields(block: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

    fun videoTarget(videoId: String) = fields { putJsonObject("target") { put("videoId", videoId) } }

    fun playlistTarget(playlistId: String) = fields { putJsonObject("target") { put("playlistId", playlistId) } }

    fun channels(
        channelId: String,
        params: String = "",
    ) = fields {
        putJsonArray("channelIds") { add(channelId) }
        put("params", params)
    }

    fun editPlaylist(
        playlistId: String,
        edits: List<PlaylistEdit>,
    ) = fields {
        put("playlistId", playlistId)
        putJsonArray("actions") {
            edits.forEach { edit ->
                addJsonObject {
                    when (edit) {
                        is PlaylistEdit.Add -> {
                            put("action", "ACTION_ADD_VIDEO")
                            put("addedVideoId", edit.videoId)
                            put("dedupeOption", "DEDUPE_OPTION_SKIP")
                        }

                        is PlaylistEdit.Remove -> {
                            put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID")
                            put("removedVideoId", edit.videoId)
                        }

                        is PlaylistEdit.Rename -> {
                            put("action", "ACTION_SET_PLAYLIST_NAME")
                            put("playlistName", edit.name)
                        }

                        is PlaylistEdit.MoveAfter -> {
                            put("action", "ACTION_MOVE_VIDEO_AFTER")
                            put("setVideoId", edit.setVideoId)
                            put("movedSetVideoIdPredecessor", edit.predecessorSetVideoId)
                        }
                    }
                }
            }
        }
    }

    fun createPlaylist(
        title: String,
        videoIds: List<String>,
        private: Boolean = true,
    ) = fields {
        put("title", title)
        if (private) put("privacyStatus", "PRIVATE")
        if (videoIds.isNotEmpty()) putJsonArray("videoIds") { videoIds.forEach { add(it) } }
    }

    fun playlistId(playlistId: String) = fields { put("playlistId", playlistId) }

    fun feedback(tokens: List<String>) = fields { putJsonArray("feedbackTokens") { tokens.forEach { add(it) } } }

    fun params(params: String) = fields { put("params", params) }

    fun videoId(videoId: String) =
        fields {
            put("videoId", videoId)
            put("racyCheckOk", true)
            put("contentCheckOk", true)
        }
}
