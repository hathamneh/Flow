package io.github.aedev.flow.data.local

/** A change the viewer made to their own library in Flow, for the signed-in YouTube account to repeat. */
sealed interface LibraryChange {
    data class Rating(
        val videoId: String,
        val rating: LibraryRating,
    ) : LibraryChange

    data class Subscription(
        val channelId: String,
        val subscribed: Boolean,
    ) : LibraryChange

    data class WatchLater(
        val videoIds: List<String>,
        val saved: Boolean,
    ) : LibraryChange

    /** Videos added to or removed from a playlist the account owns, named by its YouTube id. */
    data class PlaylistItems(
        val remoteId: String,
        val videoIds: List<String>,
        val added: Boolean,
    ) : LibraryChange

    /** An owned playlist's order changed; the outbox reads Flow's order from [localId] when it sends. */
    data class PlaylistOrder(
        val remoteId: String,
        val localId: String,
    ) : LibraryChange

    data class PlaylistRename(
        val remoteId: String,
        val name: String,
    ) : LibraryChange

    /** Someone else's playlist saved to the library, or removed from it. */
    data class PlaylistSaved(
        val playlistId: String,
        val saved: Boolean,
    ) : LibraryChange

    /**
     * An account playlist deleted in Flow. YouTube's delete doesn't work for this client, so the pull
     * just stops bringing it back.
     */
    data class PlaylistDeleted(
        val remoteId: String,
    ) : LibraryChange

    /** A video watched in Flow whose opening history ping didn't get through: sent again later. */
    data class HistoryWatched(
        val videoId: String,
        val positionMs: Long,
        val durationMs: Long,
    ) : LibraryChange

    /** Removed from history in Flow. Sent after the undo window, unless [HistoryRestored] comes first. */
    data class HistoryRemoved(
        val videoId: String,
    ) : LibraryChange

    /** A removal undone in Flow. */
    data class HistoryRestored(
        val videoId: String,
    ) : LibraryChange

    /** All of Flow's history cleared; YouTube's too only when the viewer ticked it ([alsoOnYouTube]). */
    data class HistoryCleared(
        val alsoOnYouTube: Boolean,
    ) : LibraryChange

    data class HistoryPaused(
        val paused: Boolean,
    ) : LibraryChange
}

enum class LibraryRating { LIKED, DISLIKED, NONE }

/**
 * Where the library repositories report the viewer's own changes. The YouTube outbox listens once the
 * app starts. Imports, restores, device sync and the YouTube pull never report: what they write
 * came from somewhere else, and sending it back would echo.
 *
 * A plain listener rather than an injected one because the like and subscription stores are created
 * outside Hilt, as [ViewHistory.progressListener] is.
 */
object LibraryMirror {
    @Volatile
    var listener: ((LibraryChange) -> Unit)? = null

    fun report(change: LibraryChange) {
        listener?.invoke(change)
    }
}
