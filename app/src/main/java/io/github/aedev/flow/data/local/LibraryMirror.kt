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
