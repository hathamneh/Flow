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

/**
 * The ways of asking `/player` that the sync diagnostics compare, while the plain TV request answers
 * "The page needs to be reloaded". [signedIn] false sends no token: the tracking URLs it returns would
 * be pinged signed in.
 */
enum class PlayerVariant(
    internal val client: YouTubeClient,
    internal val referer: String,
    internal val userAgent: String,
    internal val signedIn: Boolean = true,
) {
    TV(YouTubeClient.TVHTML5, "$YOUTUBE/tv", TV_USER_AGENT),
    TV_VISITOR(YouTubeClient.TVHTML5, "$YOUTUBE/tv", TV_USER_AGENT),
    TV_EMBEDDED(
        YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        "$YOUTUBE/",
        YouTubeClient.TVHTML5_SIMPLY_EMBEDDED_PLAYER.userAgent,
    ),
    WEB(YouTubeClient.WEB, "$YOUTUBE/", YouTubeClient.USER_AGENT_WEB),
    TV_SIGNED_OUT(YouTubeClient.TVHTML5, "$YOUTUBE/tv", TV_USER_AGENT, signedIn = false),
    ;

    internal val isTv get() = client.clientName == YouTubeClient.TVHTML5.clientName
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

// The Tizen TV that TV_USER_AGENT describes.
private const val TV_OS_NAME = "Tizen"
private const val TV_OS_VERSION = "4.0.0.2"
private const val TV_DEVICE_MAKE = "Samsung"
private const val TV_DEVICE_MODEL = "SmartTV"
private const val MUSIC = "https://music.youtube.com"

/** Pure builders for the signed-in request bodies, kept apart from the HTTP so they can be tested. */
internal object YouTubeAccountRequests {
    /**
     * [device] adds the TV's OS and model, which `/player` needs from a TV client: without them it
     * answers "The page needs to be reloaded", as `YouTubeClient.TV_TIZEN` found. The other calls
     * work without them and are left as they were.
     */
    fun body(
        client: AccountClient,
        hl: String,
        gl: String,
        fields: JsonObject,
        device: Boolean = false,
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
                        if (device) {
                            put("osName", TV_OS_NAME)
                            put("osVersion", TV_OS_VERSION)
                            put("deviceMake", TV_DEVICE_MAKE)
                            put("deviceModel", TV_DEVICE_MODEL)
                        }
                    }
                }
                putJsonObject("user") { put("enableSafetyMode", false) }
            }
            fields.forEach { (key, value) -> put(key, value) }
        }

    /** A `/player` request as [variant] asks it; [visitorData] is sent only by [PlayerVariant.TV_VISITOR]. */
    fun playerVariant(
        variant: PlayerVariant,
        hl: String,
        gl: String,
        videoId: String,
        signatureTimestamp: Int?,
        visitorData: String?,
    ): JsonObject =
        buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", variant.client.clientName)
                    put("clientVersion", variant.client.clientVersion)
                    put("hl", hl)
                    put("gl", gl)
                    if (variant.isTv) {
                        put("userAgent", TV_USER_AGENT)
                        putJsonObject("tvAppInfo") {
                            put("appQuality", "TV_APP_QUALITY_FULL_ANIMATION")
                            put("zylonLeftNav", true)
                        }
                        put("osName", TV_OS_NAME)
                        put("osVersion", TV_OS_VERSION)
                        put("deviceMake", TV_DEVICE_MAKE)
                        put("deviceModel", TV_DEVICE_MODEL)
                    }
                    if (variant == PlayerVariant.TV_VISITOR && visitorData != null) put("visitorData", visitorData)
                }
                if (variant == PlayerVariant.TV_EMBEDDED) putJsonObject("thirdParty") { put("embedUrl", "$YOUTUBE/") }
                putJsonObject("user") { put("enableSafetyMode", false) }
            }
            player(videoId, signatureTimestamp).forEach { (key, value) -> put(key, value) }
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

    /**
     * A `/player` request. Without the current player's [signatureTimestamp], YouTube answers
     * "UNPLAYABLE: The page needs to be reloaded" and leaves out the tracking URLs history needs.
     */
    fun player(
        videoId: String,
        signatureTimestamp: Int?,
    ) = fields {
        put("videoId", videoId)
        put("racyCheckOk", true)
        put("contentCheckOk", true)
        if (signatureTimestamp != null) {
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") { put("signatureTimestamp", signatureTimestamp) }
            }
        }
    }
}
