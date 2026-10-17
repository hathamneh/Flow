package io.github.aedev.flow.data.youtubeaccount

import io.github.aedev.flow.data.local.LibraryChange
import io.github.aedev.flow.data.local.LibraryRating
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * One change for the YouTube account, as the outbox keeps it. Each sets a state rather than toggling
 * one, so only the newest change per [key] needs sending: a like then an unlike leaves one unlike.
 */
@Serializable
sealed interface YouTubeMutation {
    val key: String

    @Serializable
    @SerialName("rate")
    data class Rate(
        val videoId: String,
        val rating: LibraryRating,
    ) : YouTubeMutation {
        override val key get() = "$RATE_PREFIX$videoId"
    }

    @Serializable
    @SerialName("subscribe")
    data class Subscribe(
        val channelId: String,
        val on: Boolean,
    ) : YouTubeMutation {
        override val key get() = "$SUBSCRIBE_PREFIX$channelId"
    }

    @Serializable
    @SerialName("playlist_item")
    data class PlaylistItem(
        val playlistId: String,
        val videoId: String,
        val add: Boolean,
    ) : YouTubeMutation {
        override val key get() = "$PLAYLIST_PREFIX$playlistId:$videoId"
    }

    /** Make an owned playlist's order match Flow's, read when sent; [localId] is Flow's row. */
    @Serializable
    @SerialName("playlist_order")
    data class PlaylistOrder(
        val playlistId: String,
        val localId: String,
    ) : YouTubeMutation {
        override val key get() = "$ORDER_PREFIX$playlistId"
    }

    @Serializable
    @SerialName("playlist_rename")
    data class PlaylistRename(
        val playlistId: String,
        val name: String,
    ) : YouTubeMutation {
        override val key get() = "$RENAME_PREFIX$playlistId"
    }

    /** Someone else's playlist in the library, or not. */
    @Serializable
    @SerialName("playlist_save")
    data class PlaylistSave(
        val playlistId: String,
        val saved: Boolean,
    ) : YouTubeMutation {
        override val key get() = "$SAVE_PREFIX$playlistId"
    }

    companion object {
        const val RATE_PREFIX = "rate:"
        const val SUBSCRIBE_PREFIX = "sub:"
        const val PLAYLIST_PREFIX = "pl:"
        const val ORDER_PREFIX = "plorder:"
        const val RENAME_PREFIX = "plname:"
        const val SAVE_PREFIX = "plsave:"

        /** YouTube's id for Watch later, in edits and in outbox keys. */
        const val WATCH_LATER = "WL"

        private val json = Json { ignoreUnknownKeys = true }

        fun encode(mutation: YouTubeMutation): String = json.encodeToString(serializer(), mutation)

        /** Null for a payload this version can't read, such as one written by a newer build. */
        fun decode(payload: String): YouTubeMutation? = runCatching { json.decodeFromString(serializer(), payload) }.getOrNull()

        fun from(change: LibraryChange): List<YouTubeMutation> =
            when (change) {
                is LibraryChange.Rating -> listOf(Rate(change.videoId, change.rating))

                is LibraryChange.Subscription -> listOf(Subscribe(change.channelId, change.subscribed))

                is LibraryChange.WatchLater -> change.videoIds.distinct().map { PlaylistItem(WATCH_LATER, it, change.saved) }

                is LibraryChange.PlaylistItems -> change.videoIds.distinct().map { PlaylistItem(change.remoteId, it, change.added) }

                is LibraryChange.PlaylistOrder -> listOf(PlaylistOrder(change.remoteId, change.localId))

                is LibraryChange.PlaylistRename -> listOf(PlaylistRename(change.remoteId, change.name))

                is LibraryChange.PlaylistSaved -> listOf(PlaylistSave(change.playlistId, change.saved))

                // Not sent: the outbox keeps it from coming back on the next pull instead.
                is LibraryChange.PlaylistDeleted -> emptyList()
            }

        /** The ids behind the queued keys that start with [prefix], for masking the pull. */
        fun idsFor(
            keys: Collection<String>,
            prefix: String,
        ): Set<String> = keys.filter { it.startsWith(prefix) }.mapTo(HashSet()) { it.removePrefix(prefix) }
    }
}

/** What one send attempt came to, and so what the outbox does with the change. */
enum class SendOutcome {
    /** YouTube took it: drop it. */
    SENT,

    /** Offline, throttled or a server error: keep it and try again later. */
    RETRY,

    /** YouTube refused it outright: drop it and count it as couldn't sync. Flow keeps its own state. */
    REJECTED,

    /** The account needs signing in again: keep it until it is. */
    SIGNED_OUT,
    ;

    companion object {
        fun of(error: Throwable?): SendOutcome =
            when (error) {
                null -> {
                    SENT
                }

                is YouTubeUnauthorizedException -> {
                    SIGNED_OUT
                }

                is YouTubeHttpException -> {
                    when {
                        error.code == HTTP_TOO_MANY_REQUESTS || error.code == HTTP_TIMEOUT || error.code >= HTTP_SERVER_ERROR -> RETRY
                        else -> REJECTED
                    }
                }

                is IOException -> {
                    RETRY
                }

                else -> {
                    REJECTED
                }
            }

        private const val HTTP_TIMEOUT = 408
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500
    }
}
