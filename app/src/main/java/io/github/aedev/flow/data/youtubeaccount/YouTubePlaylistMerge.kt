package io.github.aedev.flow.data.youtubeaccount

import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.local.entity.PlaylistEntity
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings the account's playlists into Flow. A playlist the account owns becomes one of the viewer's
 * own, editable here, and follows YouTube both ways: entries added or removed there are added or
 * removed here, compared with the last pull as likes are, and Flow's edits go back through the
 * [YouTubeOutbox]. Other people's playlists saved to the library stay saved copies that match YouTube.
 *
 * A playlist deleted or unsaved in Flow isn't brought back while YouTube still lists it, and one gone
 * from a complete read of the list is removed here.
 */
@Singleton
class YouTubePlaylistMerge
    @Inject
    constructor(
        private val api: YouTubeAccountApi,
        private val outbox: YouTubeOutbox,
        private val snapshots: YouTubeRemoteSnapshot,
        private val playlists: PlaylistRepository,
    ) {
        /** The number of playlists brought up to date. */
        suspend fun pull(
            auth: YouTubeAccountApi.Auth,
            accountId: String,
        ): Int {
            val found = LinkedHashMap<String, RemotePlaylist>()
            val complete =
                api.browsePages(auth, PLAYLISTS, LIST_PAGES) { page ->
                    val before = found.size
                    YouTubeAccountParsers.playlists(page).forEach { found.putIfAbsent(it.playlistId, it) }
                    found.size > before
                }
            val deleted = snapshots.deletedPlaylists(accountId)
            val wanted =
                found.values.take(MAX_PLAYLISTS).filter { playlist ->
                    playlist.playlistId !in deleted && playlists.getPlaylistEntity(playlist.playlistId).isAccountCopy()
                }
            var updated = 0
            // A few playlists browse together instead of all sixty in a row.
            wanted.chunked(PARALLEL).forEach { round ->
                val walks =
                    coroutineScope {
                        round
                            .map { playlist ->
                                async(PerformanceDispatcher.networkIO) { playlist to walk(auth, playlist.playlistId) }
                            }.awaitAll()
                    }
                walks.forEach { (playlist, walk) ->
                    val done = if (walk.editable) mergeOwned(auth, accountId, playlist) else keepSaved(playlist, walk.videos)
                    if (done) updated++
                }
            }
            outbox.exclusive { reconcileList(accountId, found.keys.toList(), complete, deleted) }
            return updated
        }

        /** A row Flow may fill from the account: new, or already linked to it, or a saved copy. */
        private fun PlaylistEntity?.isAccountCopy() = this == null || remoteId != null || !isUserCreated

        private suspend fun keepSaved(
            playlist: RemotePlaylist,
            videos: VideoWalk,
        ): Boolean {
            if (videos.items.isEmpty()) return false
            // Once the account's own, always: a page read without its edit controls doesn't demote it.
            if (playlists.getPlaylistEntity(playlist.playlistId)?.remoteOwned == true) return false
            playlists.saveAccountPlaylist(
                playlist.playlistId,
                playlist.title,
                thumbnail(playlist, videos),
                videos.isMostlyMusic(),
                owned = false,
            )
            playlists.syncSavedPlaylistVideos(playlist.playlistId, videos.items.map { it.toVideo() })
            return true
        }

        /** Read again while no change is being sent, so Flow's own edits in flight aren't mistaken for YouTube's. */
        private suspend fun mergeOwned(
            auth: YouTubeAccountApi.Auth,
            accountId: String,
            playlist: RemotePlaylist,
        ): Boolean =
            outbox.exclusive {
                val id = playlist.playlistId
                val videos = walk(auth, id).videos
                val list = YouTubeRemoteSnapshot.playlistList(id)
                val local = playlists.getPlaylistEntity(id)
                if (local == null || !local.remoteOwned) {
                    // First sight, or a saved copy from before Flow could edit account playlists: take YouTube's.
                    if (local == null) {
                        playlists.saveAccountPlaylist(id, playlist.title, thumbnail(playlist, videos), videos.isMostlyMusic(), owned = true)
                    } else {
                        playlists.linkAccountPlaylist(id, owned = true)
                    }
                    playlists.syncSavedPlaylistVideos(id, videos.items.map { it.toVideo() })
                    snapshots.put(accountId, list, videos.items.map { it.videoId })
                    return@exclusive true
                }
                val keys = outbox.pendingKeys(accountId)
                if (YouTubeMutation.RENAME_PREFIX + id !in keys && playlist.title.isNotBlank() && playlist.title != local.name) {
                    playlists.updatePlaylistName(id, playlist.title, mirror = false)
                }
                val pending = YouTubeMutation.idsFor(keys, "${YouTubeMutation.PLAYLIST_PREFIX}$id:")
                val diff =
                    remoteDiff(
                        previous = snapshots.get(accountId, list)?.ids,
                        fresh = videos.items.map { it.videoId },
                        complete = videos.complete,
                        pending = pending,
                        positional = true,
                    )
                val have = playlists.getOrderedVideoIds(id).toHashSet()
                val added = diff.added.toHashSet()
                playlists.addVideosToPlaylist(
                    id,
                    videos.items.filter { it.videoId in added && it.videoId !in have }.map { it.toVideo() },
                    mirror = false,
                )
                diff.removed.filter { it in have }.forEach { playlists.removeVideoFromPlaylist(id, it, mirror = false) }
                // With nothing of Flow's waiting, YouTube's order is the order.
                if (videos.complete && pending.isEmpty() && YouTubeMutation.ORDER_PREFIX + id !in keys) followOrder(id, videos)
                snapshots.put(accountId, list, diff.snapshot)
                true
            }

        private suspend fun followOrder(
            id: String,
            videos: VideoWalk,
        ) {
            val current = playlists.getOrderedVideoIds(id)
            val inFlow = current.toHashSet()
            val remote =
                videos.items
                    .map { it.videoId }
                    .filter { it in inFlow }
                    .distinct()
            val order = remote + current.filterNot { it in remote.toHashSet() }
            if (order != current) playlists.reorderVideosInPlaylist(id, order, mirror = false)
        }

        /**
         * Playlists gone from a complete read of the account's list were deleted or unsaved on YouTube,
         * so they go here too, and tombstones for them are no longer needed.
         */
        private suspend fun reconcileList(
            accountId: String,
            found: List<String>,
            complete: Boolean,
            deleted: Set<String>,
        ) {
            val pending = YouTubeMutation.idsFor(outbox.pendingKeys(accountId), YouTubeMutation.SAVE_PREFIX)
            val previous = snapshots.get(accountId, YouTubeRemoteSnapshot.PLAYLISTS)?.ids
            val diff = remoteDiff(previous, found, complete, pending, positional = false)
            diff.removed.forEach { id ->
                val local = playlists.getPlaylistEntity(id)
                if (local?.remoteId == id) playlists.deletePlaylist(id, mirror = false)
                snapshots.drop(accountId, YouTubeRemoteSnapshot.playlistList(id))
            }
            if (complete && found.isNotEmpty()) snapshots.setDeletedPlaylists(accountId, deleted.intersect(found.toSet()))
            snapshots.put(accountId, YouTubeRemoteSnapshot.PLAYLISTS, diff.snapshot)
        }

        private data class PlaylistWalk(
            val videos: VideoWalk,
            val editable: Boolean,
        )

        /** A playlist's videos, and whether the first page offers the owner's edit controls. */
        private suspend fun walk(
            auth: YouTubeAccountApi.Auth,
            playlistId: String,
        ): PlaylistWalk {
            val found = LinkedHashMap<String, RemoteHistoryItem>()
            var editable = false
            var first = true
            val complete =
                api.browsePages(auth, BROWSE_PREFIX + playlistId, PLAYLIST_PAGES) { page ->
                    if (first) editable = YouTubeAccountParsers.isEditable(page)
                    first = false
                    val before = found.size
                    YouTubeAccountParsers.videos(page).forEach { found.putIfAbsent(it.videoId, it) }
                    found.size > before
                }
            return PlaylistWalk(VideoWalk(found.values.toList(), complete), editable)
        }

        private fun thumbnail(
            playlist: RemotePlaylist,
            videos: VideoWalk,
        ) = playlist.thumbnailUrl.ifBlank {
            videos.items
                .firstOrNull()
                ?.thumbnailUrl
                .orEmpty()
        }

        private fun VideoWalk.isMostlyMusic() = items.count { it.isMusic } * 2 > items.size

        private companion object {
            const val PLAYLISTS = "FEplaylist_aggregation"
            const val BROWSE_PREFIX = "VL"
            const val LIST_PAGES = 5
            const val PLAYLIST_PAGES = 10
            const val MAX_PLAYLISTS = 60
            const val PARALLEL = 4
        }
    }
