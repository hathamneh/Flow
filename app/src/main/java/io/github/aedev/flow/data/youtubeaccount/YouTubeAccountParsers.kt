package io.github.aedev.flow.data.youtubeaccount

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Pure readers over the TV InnerTube responses the account features use. The TV renderers nest
 * differently from one release to the next, so each reader finds its renderer anywhere in the tree
 * by name rather than following one fixed path — a moved wrapper then costs nothing.
 */
internal object YouTubeAccountParsers {
    private const val CHANNEL_PREFIX = "UC"
    private const val TILE_VIDEO = "TILE_CONTENT_TYPE_VIDEO"
    private const val TILE_SHORTS = "TILE_CONTENT_TYPE_SHORTS"
    private const val TILE_PLAYLIST = "TILE_CONTENT_TYPE_PLAYLIST"
    private const val PLAYLIST_BROWSE_PREFIX = "VL"
    private val BUILT_IN_PLAYLISTS = setOf("LL", "LM", "WL")
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    private val CHANNEL_ID = Regex("UC[A-Za-z0-9_-]{22}")

    fun accounts(root: JsonElement): List<RemoteIdentity> =
        root
            .objectsWithKey("accountItem")
            .mapNotNull { it["accountItem"] as? JsonObject }
            .filter { it.bool("isDisabled") != true }
            .mapNotNull { item ->
                val name = item.text("accountName") ?: return@mapNotNull null
                RemoteIdentity(
                    name = name,
                    email = item.text("accountByline").orEmpty(),
                    handle = item.text("channelHandle").orEmpty(),
                    avatarUrl = item.obj("accountPhoto")?.bestThumbnail().orEmpty(),
                    pageId =
                        item
                            .obj("serviceEndpoint")
                            ?.objectsWithKey("pageIdToken")
                            ?.firstNotNullOfOrNull { it.obj("pageIdToken")?.string("pageId") },
                    isSelected = item.bool("isSelected") == true,
                )
            }.toList()

    fun trackingUrls(root: JsonElement): PlaybackTrackingUrls? {
        val tracking = (root as? JsonObject)?.obj("playbackTracking") ?: return null
        val playback = tracking.obj("videostatsPlaybackUrl")?.string("baseUrl") ?: return null
        return PlaybackTrackingUrls(
            playbackUrl = playback,
            watchtimeUrl = tracking.obj("videostatsWatchtimeUrl")?.string("baseUrl"),
        )
    }

    /** Subscribed channels: the TV subscriptions page lists one tab (or tile) per channel. */
    fun channels(root: JsonElement): List<RemoteChannel> {
        val found = LinkedHashMap<String, RemoteChannel>()
        root.objectsWithKey("tabRenderer").forEach { holder ->
            val tab = holder.obj("tabRenderer") ?: return@forEach
            val id = tab.browseId() ?: return@forEach
            val name = tab.text("title") ?: return@forEach
            found.putIfAbsent(id, RemoteChannel(id, name, tab.obj("thumbnail")?.bestThumbnail().orEmpty()))
        }
        root.objectsWithKey("tileRenderer").forEach { holder ->
            val tile = holder.obj("tileRenderer") ?: return@forEach
            val id = tile.obj("onSelectCommand")?.browseId() ?: return@forEach
            val name = tile.tileTitle() ?: return@forEach
            found.putIfAbsent(id, RemoteChannel(id, name, tile.tileThumbnail()))
        }
        return found.values.toList()
    }

    /**
     * The library's playlists. Liked videos, liked music and Watch later are left out: they have their
     * own homes in Flow and are read from their own pages.
     */
    fun playlists(root: JsonElement): List<RemotePlaylist> {
        val found = LinkedHashMap<String, RemotePlaylist>()
        root.objectsWithKey("tileRenderer").forEach { holder ->
            val tile = holder.obj("tileRenderer") ?: return@forEach
            if (tile.string("contentType") != TILE_PLAYLIST) return@forEach
            val id =
                tile.string("contentId")
                    ?: tile
                        .obj("onSelectCommand")
                        ?.obj("browseEndpoint")
                        ?.string("browseId")
                        ?.removePrefix(PLAYLIST_BROWSE_PREFIX)
                    ?: return@forEach
            if (id in BUILT_IN_PLAYLISTS) return@forEach
            val title = tile.tileTitle() ?: return@forEach
            found.putIfAbsent(id, RemotePlaylist(id, title, tile.tileThumbnail()))
        }
        return found.values.toList()
    }

    /** Video tiles, in page order: watch history newest first, or a playlist's videos in its order. */
    fun videos(root: JsonElement): List<RemoteHistoryItem> = history(root)

    /** Watch history, newest first, as the TV history page orders it. */
    fun history(root: JsonElement): List<RemoteHistoryItem> {
        val found = LinkedHashMap<String, RemoteHistoryItem>()
        root.objectsWithKey("tileRenderer").forEach { holder ->
            val tile = holder.obj("tileRenderer") ?: return@forEach
            val type = tile.string("contentType")
            if (type != null && type != TILE_VIDEO && type != TILE_SHORTS) return@forEach
            val videoId =
                tile.string("contentId")?.takeIf { VIDEO_ID.matches(it) }
                    ?: tile
                        .obj("onSelectCommand")
                        ?.obj("watchEndpoint")
                        ?.string("videoId")
                    ?: return@forEach
            val title = tile.tileTitle() ?: return@forEach
            val overlays = tile.objectsWithKey("thumbnailOverlayResumePlaybackRenderer")
            val lengthText =
                tile
                    .objectsWithKey("thumbnailOverlayTimeStatusRenderer")
                    .firstNotNullOfOrNull { it.obj("thumbnailOverlayTimeStatusRenderer")?.text("text") }
            found.putIfAbsent(
                videoId,
                RemoteHistoryItem(
                    videoId = videoId,
                    title = title,
                    channelName = tile.tileFirstLine().orEmpty(),
                    channelId = tile.objectsWithKey("browseEndpoint").firstNotNullOfOrNull { it.browseId() }.orEmpty(),
                    thumbnailUrl = tile.tileThumbnail(),
                    durationMs = lengthText?.let(::parseClockMs) ?: 0L,
                    percentWatched =
                        overlays
                            .firstNotNullOfOrNull {
                                it.obj("thumbnailOverlayResumePlaybackRenderer")?.int("percentDurationWatched")
                            }?.coerceIn(0, 100) ?: 0,
                    isMusic = tile.objectsWithKey("musicVideoType").any(),
                ),
            )
        }
        return found.values.toList()
    }

    /** The token for the next page of a browse result, in either continuation shape YouTube uses. */
    fun continuation(root: JsonElement): String? =
        root.objectsWithKey("nextContinuationData").firstNotNullOfOrNull { it.obj("nextContinuationData")?.string("continuation") }
            ?: root
                .objectsWithKey("continuationCommand")
                .firstNotNullOfOrNull { it.obj("continuationCommand")?.string("token") }

    /** "1:02:03" or "12:34" → milliseconds; null for anything that is not a clock. */
    fun parseClockMs(text: String): Long? {
        val parts = text.trim().split(':')
        if (parts.size !in 2..3) return null
        var seconds = 0L
        for (part in parts) {
            val value = part.toLongOrNull() ?: return null
            seconds = seconds * 60 + value
        }
        return seconds * 1000
    }

    private fun JsonObject.browseId(): String? {
        val id = objectsWithKey("browseEndpoint").firstNotNullOfOrNull { it.obj("browseEndpoint")?.string("browseId") }
        return id?.takeIf { it.startsWith(CHANNEL_PREFIX) && CHANNEL_ID.matches(it) }
    }

    private fun JsonObject.tileTitle(): String? = obj("metadata")?.obj("tileMetadataRenderer")?.text("title")

    private fun JsonObject.tileFirstLine(): String? =
        obj("metadata")
            ?.obj("tileMetadataRenderer")
            ?.objectsWithKey("lineItemRenderer")
            ?.firstNotNullOfOrNull { it.obj("lineItemRenderer")?.text("text") }

    private fun JsonObject.tileThumbnail(): String =
        obj("header")?.objectsWithKey("thumbnail")?.firstNotNullOfOrNull { it.obj("thumbnail")?.bestThumbnail() }.orEmpty()

    private fun JsonObject.bestThumbnail(): String? =
        (this["thumbnails"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.string("url") }
            ?.lastOrNull()
            ?.let { if (it.startsWith("//")) "https:$it" else it }
}

/** Every object in the tree (this one included) that has [key] as a direct field. */
internal fun JsonElement.objectsWithKey(key: String): Sequence<JsonObject> =
    sequence {
        val stack = ArrayDeque<JsonElement>()
        stack.addLast(this@objectsWithKey)
        while (stack.isNotEmpty()) {
            when (val element = stack.removeLast()) {
                is JsonObject -> {
                    if (key in element) yield(element)
                    element.values.reversed().forEach(stack::addLast)
                }

                is JsonArray -> {
                    element.reversed().forEach(stack::addLast)
                }

                else -> {
                    Unit
                }
            }
        }
    }

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

/** InnerTube text: `simpleText`, or the joined `runs`. */
internal fun JsonObject.text(key: String): String? {
    val node = this[key] as? JsonObject ?: return (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    node.string("simpleText")?.let { return it.takeIf(String::isNotBlank) }
    val runs = node["runs"] as? JsonArray ?: return node.string("content")?.takeIf(String::isNotBlank)
    return runs
        .joinToString("") { run -> (run as? JsonObject)?.string("text").orEmpty() }
        .takeIf { it.isNotBlank() }
}
