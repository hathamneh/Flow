package io.github.aedev.flow.data.local

import io.github.aedev.flow.data.local.dao.PlaylistDao
import io.github.aedev.flow.data.local.entity.PlaylistEntity

/**
 * Reports the viewer's playlist edits to [LibraryMirror]: Watch later, the account's own playlists
 * (rows linked with [PlaylistEntity.remoteOwned]) and saving other people's playlists. Playlists only
 * in Flow report nothing.
 */
internal class PlaylistMirroring(
    private val dao: PlaylistDao,
) {
    suspend fun items(
        playlistId: String,
        videoIds: List<String>,
        added: Boolean,
    ) {
        if (videoIds.isEmpty()) return
        if (playlistId == PlaylistRepository.WATCH_LATER_ID) {
            LibraryMirror.report(LibraryChange.WatchLater(videoIds, added))
            return
        }
        val remoteId = owned(playlistId)?.remoteId ?: return
        LibraryMirror.report(LibraryChange.PlaylistItems(remoteId, videoIds, added))
    }

    /** An undo puts entries back where they were, which YouTube can only do with a move after the add. */
    suspend fun restored(
        playlistId: String,
        videoIds: List<String>,
    ) {
        items(playlistId, videoIds, added = true)
        order(playlistId)
    }

    suspend fun order(playlistId: String) {
        val remoteId = owned(playlistId)?.remoteId ?: return
        LibraryMirror.report(LibraryChange.PlaylistOrder(remoteId, playlistId))
    }

    suspend fun renamed(
        playlistId: String,
        name: String,
    ) {
        val playlist = owned(playlistId) ?: return
        val remoteId = playlist.remoteId ?: return
        if (name.isNotBlank()) LibraryMirror.report(LibraryChange.PlaylistRename(remoteId, name))
    }

    fun saved(
        playlistId: String,
        saved: Boolean,
    ) {
        if (isSavablePlaylistId(playlistId)) LibraryMirror.report(LibraryChange.PlaylistSaved(playlistId, saved))
    }

    /** Call with the row as it was before it was deleted or unsaved. */
    fun deleted(playlist: PlaylistEntity?) {
        playlist ?: return
        playlist.remoteId?.let { LibraryMirror.report(LibraryChange.PlaylistDeleted(it)) }
        if (!playlist.isUserCreated) saved(playlist.id, saved = false)
    }

    private suspend fun owned(playlistId: String): PlaylistEntity? =
        dao.getPlaylist(playlistId)?.takeIf {
            it.remoteOwned &&
                it.remoteId != null
        }

    private companion object {
        /** Ordinary playlists and albums; mixes and other browse ids can't be saved to an account. */
        fun isSavablePlaylistId(id: String) = id.startsWith("PL") || id.startsWith("OLAK5uy_")
    }
}
