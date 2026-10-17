package io.github.aedev.flow.data.youtubeaccount

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.ChannelSubscription
import io.github.aedev.flow.data.local.LikedVideoInfo
import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.model.Video
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings the account's subscriptions, likes and Watch later into Flow both ways round: what was added
 * on YouTube is added here, and what was removed there is removed here. Each list is compared with how
 * it looked at the last pull ([YouTubeRemoteSnapshot]), so a change made in Flow isn't undone, and
 * anything still queued in the [YouTubeOutbox] is left as Flow has it.
 *
 * Everything here writes through the import paths, which don't report back to the outbox.
 */
@Singleton
class YouTubeLibraryMerge
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val api: YouTubeAccountApi,
        private val outbox: YouTubeOutbox,
        private val snapshots: YouTubeRemoteSnapshot,
        private val playlists: PlaylistRepository,
    ) {
        private val likes by lazy { LikedVideosRepository.getInstance(context) }
        private val subscriptions by lazy { SubscriptionRepository.getInstance(context) }

        /** The channels newly subscribed to here. */
        suspend fun subscriptions(
            auth: YouTubeAccountApi.Auth,
            accountId: String,
            maxPages: Int,
        ): List<ChannelSubscription> =
            outbox.exclusive {
                val found = LinkedHashMap<String, RemoteChannel>()
                val complete =
                    api.browsePages(auth, CHANNELS, maxPages) { page ->
                        val before = found.size
                        YouTubeAccountParsers.channels(page).forEach { found.putIfAbsent(it.channelId, it) }
                        found.size > before
                    }
                val previous = snapshots.get(accountId, RemoteCollection.SUBSCRIPTIONS)
                val pending = pending(accountId, YouTubeMutation.SUBSCRIBE_PREFIX)
                // The channel list has no useful order, so a removal needs the whole list.
                val diff = remoteDiff(previous?.ids, found.keys.toList(), complete, pending, positional = false)
                val local = subscriptions.getAllSubscriptionIds()
                val tombstones = subscriptions.unsubscribedTombstones()
                val added =
                    diff.added
                        .filterNot { it in local || unsubscribedSince(tombstones[it], previous) }
                        .mapNotNull(found::get)
                        .map { ChannelSubscription(channelId = it.channelId, channelName = it.name, channelThumbnail = it.thumbnailUrl) }
                subscriptions.subscribeAll(added)
                diff.removed.filter { it in local }.forEach { subscriptions.unsubscribe(it, mirror = false) }
                snapshots.put(accountId, RemoteCollection.SUBSCRIPTIONS, diff.snapshot)
                added
            }

        /**
         * A channel unsubscribed from in Flow before the last pull, or before any, while it wasn't
         * synced yet: still not wanted. One unsubscribed from earlier and subscribed to again on YouTube
         * since is.
         */
        private fun unsubscribedSince(
            at: Long?,
            previous: Snapshot?,
        ) = at != null && (previous == null || at >= previous.takenAt)

        /** The likes newly added here, from Liked videos or, with [music], Liked music. */
        suspend fun likes(
            auth: YouTubeAccountApi.Auth,
            accountId: String,
            music: Boolean,
            maxPages: Int,
        ): List<LikedVideoInfo> =
            outbox.exclusive {
                val collection = if (music) RemoteCollection.MUSIC_LIKES else RemoteCollection.LIKES
                val other = if (music) RemoteCollection.LIKES else RemoteCollection.MUSIC_LIKES
                val walk = collectVideos(auth, if (music) LIKED_MUSIC else LIKED_VIDEOS, maxPages)
                val diff = diff(accountId, collection, walk, YouTubeMutation.RATE_PREFIX)
                val now = System.currentTimeMillis()
                val byId = walk.items.associateBy { it.videoId }
                val infos =
                    diff.added.mapNotNull(byId::get).mapIndexed { index, item ->
                        LikedVideoInfo(
                            videoId = item.videoId,
                            title = item.title,
                            thumbnail = item.thumbnailUrl,
                            channelName = item.channelName,
                            likedAt = now - index * ORDER_STEP_MS,
                            isMusic = music,
                            channelId = item.channelId.ifBlank { null },
                            durationSeconds = (item.durationMs / MS_PER_SECOND).toInt(),
                        )
                    }
                val added = likes.importLikes(infos)
                // A video still in the other list is still liked: a music video can be in both.
                val stillLiked =
                    snapshots
                        .get(accountId, other)
                        ?.ids
                        .orEmpty()
                        .toHashSet()
                diff.removed
                    .filter { it !in stillLiked && likes.likeState(it) == LIKED }
                    .forEach { likes.removeLikeState(it, mirror = false) }
                snapshots.put(accountId, collection, diff.snapshot)
                added
            }

        suspend fun watchLater(
            auth: YouTubeAccountApi.Auth,
            accountId: String,
            maxPages: Int,
        ) {
            outbox.exclusive {
                val walk = collectVideos(auth, WATCH_LATER, maxPages)
                val diff = diff(accountId, RemoteCollection.WATCH_LATER, walk, WATCH_LATER_KEY_PREFIX)
                val added = diff.added.toHashSet()
                // Flow lists Watch later newest-added first, so add YouTube's list from its end.
                walk.items.asReversed().filter { it.videoId in added }.forEach { item ->
                    if (!playlists.isInWatchLater(item.videoId)) playlists.addToWatchLater(item.toVideo(), mirror = false)
                }
                diff.removed
                    .filter { playlists.isInWatchLater(it) }
                    .forEach { playlists.removeFromWatchLater(it, mirror = false) }
                snapshots.put(accountId, RemoteCollection.WATCH_LATER, diff.snapshot)
            }
        }

        private suspend fun diff(
            accountId: String,
            collection: RemoteCollection,
            walk: VideoWalk,
            keyPrefix: String,
        ): RemoteDiff =
            remoteDiff(
                previous = snapshots.get(accountId, collection)?.ids,
                fresh = walk.items.map { it.videoId },
                complete = walk.complete,
                pending = pending(accountId, keyPrefix),
                positional = true,
            )

        private suspend fun pending(
            accountId: String,
            prefix: String,
        ) = YouTubeMutation.idsFor(outbox.pendingKeys(accountId), prefix)

        private suspend fun collectVideos(
            auth: YouTubeAccountApi.Auth,
            browseId: String,
            maxPages: Int,
        ): VideoWalk = api.collectVideos(auth, browseId, maxPages)

        private companion object {
            // The TV app's full channel list; FEsubscriptions is the uploads feed, not the channels.
            const val CHANNELS = "FEchannels"
            const val LIKED_VIDEOS = "VLLL"
            const val LIKED_MUSIC = "VLLM"
            const val WATCH_LATER = "VLWL"
            const val LIKED = "LIKED"
            const val WATCH_LATER_KEY_PREFIX = "${YouTubeMutation.PLAYLIST_PREFIX}${YouTubeMutation.WATCH_LATER}:"
            const val ORDER_STEP_MS = 1_000L
            const val MS_PER_SECOND = 1000L
        }
    }

/** The videos of a YouTube list, newest page first, and whether the walk reached its end. */
data class VideoWalk(
    val items: List<RemoteHistoryItem>,
    val complete: Boolean,
)

internal suspend fun YouTubeAccountApi.collectVideos(
    auth: YouTubeAccountApi.Auth,
    browseId: String,
    maxPages: Int,
): VideoWalk {
    val found = LinkedHashMap<String, RemoteHistoryItem>()
    val complete =
        browsePages(auth, browseId, maxPages) { page ->
            val before = found.size
            YouTubeAccountParsers.videos(page).forEach { found.putIfAbsent(it.videoId, it) }
            found.size > before
        }
    return VideoWalk(found.values.toList(), complete)
}

internal fun RemoteHistoryItem.toVideo() =
    Video(
        id = videoId,
        title = title,
        channelName = channelName,
        channelId = channelId,
        thumbnailUrl = thumbnailUrl,
        duration = (durationMs / 1000L).toInt(),
        viewCount = 0L,
        uploadDate = "",
        isMusic = isMusic,
    )
