package io.github.aedev.flow.data.youtubeaccount

import io.github.aedev.flow.data.local.PlaylistRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Sends the outbox's playlist changes: entries, order, names and saved playlists. */
@Singleton
class YouTubePlaylistSender
    @Inject
    constructor(
        private val api: YouTubeAccountApi,
        private val playlists: PlaylistRepository,
    ) {
        suspend fun send(
            auth: YouTubeAccountApi.Auth,
            mutation: YouTubeMutation,
        ) {
            when (mutation) {
                is YouTubeMutation.PlaylistItem -> {
                    val edit = if (mutation.add) PlaylistEdit.Add(mutation.videoId) else PlaylistEdit.Remove(mutation.videoId)
                    api.editPlaylist(auth, mutation.playlistId, listOf(edit))
                }

                is YouTubeMutation.PlaylistOrder -> {
                    sendOrder(auth, mutation)
                }

                is YouTubeMutation.PlaylistRename -> {
                    api.editPlaylist(auth, mutation.playlistId, listOf(PlaylistEdit.Rename(mutation.name)))
                }

                is YouTubeMutation.PlaylistSave -> {
                    api.setPlaylistSaved(auth, mutation.playlistId, mutation.saved)
                }

                else -> {
                    error("Not a playlist change: ${mutation.key}")
                }
            }
        }

        /** Reads both orders now, so a burst of drags sends one set of moves for where they ended up. */
        private suspend fun sendOrder(
            auth: YouTubeAccountApi.Auth,
            mutation: YouTubeMutation.PlaylistOrder,
        ) {
            val wanted = playlists.getOrderedVideoIds(mutation.localId)
            if (wanted.size < 2) return
            val moves = planMoves(api.playlistEntries(auth, mutation.playlistId), wanted)
            moves.chunked(MOVES_PER_EDIT).forEach { api.editPlaylist(auth, mutation.playlistId, it) }
        }

        private companion object {
            const val MOVES_PER_EDIT = 50
        }
    }

/** Every entry of a playlist on YouTube, in order, up to [maxPages] pages. */
internal suspend fun YouTubeAccountApi.playlistEntries(
    auth: YouTubeAccountApi.Auth,
    playlistId: String,
    maxPages: Int = ENTRY_PAGES,
): List<PlaylistEntry> {
    val entries = mutableListOf<PlaylistEntry>()
    browsePages(auth, "VL$playlistId", maxPages) { page ->
        val found = YouTubeAccountParsers.playlistEntries(page)
        entries += found
        found.isNotEmpty()
    }
    return entries
}

private const val ENTRY_PAGES = 20
