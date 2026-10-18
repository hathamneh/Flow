package io.github.aedev.flow.data.youtubeaccount

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.ChannelSubscription
import io.github.aedev.flow.data.local.LibraryRating
import io.github.aedev.flow.data.local.LikedVideoInfo
import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.model.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When a video stays open, asks the account's watch page (`/next`) whether the video is liked and the
 * channel subscribed, and corrects Flow's copy, so a like or subscription changed on another device
 * shows at once rather than at the next sync. The buttons follow the stores, so they update on their own.
 *
 * Cheap by design: it waits until the video has stayed open a moment (skipping through videos reads
 * none of them), reads each video at most once in [FRESH_MS], and leaves anything Flow still has
 * queued for YouTube alone. Each read or skip is logged, so its cost per playback can be measured.
 */
@Singleton
class YouTubeReadThrough
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val accounts: YouTubeAccountManager,
        private val api: YouTubeAccountApi,
        private val outbox: YouTubeOutbox,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val likes by lazy { LikedVideosRepository.getInstance(context) }
        private val subscriptions by lazy { SubscriptionRepository.getInstance(context) }

        // Touched only from onVideoOpened's caller (the main thread) and the single pending job.
        private val readAt =
            object : LinkedHashMap<String, Long>(MAX_REMEMBERED, LOAD_FACTOR, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > MAX_REMEMBERED
            }
        private var pending: Job? = null
        private var reads = 0

        /** The player opened [video]; a newer call cancels a read still waiting. */
        fun onVideoOpened(video: Video) {
            if (video.id.isBlank() || video.channelId.isBlank()) return
            pending?.cancel()
            pending =
                scope.launch {
                    delay(SETTLE_MS)
                    val last = synchronized(readAt) { readAt[video.id] }
                    if (last != null && System.currentTimeMillis() - last < FRESH_MS) {
                        Log.i(TAG, "skip ${video.id}: read ${(System.currentTimeMillis() - last) / MS_PER_MINUTE} min ago")
                        return@launch
                    }
                    try {
                        read(video)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Best effort: the next sync catches up anyway.
                        Log.w(TAG, "Could not read ${video.id} from YouTube", e)
                    }
                }
        }

        private suspend fun read(video: Video) {
            accounts.withActiveAuth { account, auth ->
                val page = api.next(auth, video.id)
                synchronized(readAt) { readAt[video.id] = System.currentTimeMillis() }
                reads++
                outbox.exclusive {
                    val keys = outbox.pendingKeys(account.id)
                    val fix =
                        readThroughFix(
                            remoteLike = YouTubeAccountParsers.likeStatus(page, video.id),
                            localLike = likes.likeState(video.id),
                            likePending = YouTubeMutation.RATE_PREFIX + video.id in keys,
                            remoteSubscribed = YouTubeAccountParsers.isSubscribed(page, video.channelId),
                            localSubscribed = subscriptions.isSubscribed(video.channelId).first(),
                            subscriptionPending = YouTubeMutation.SUBSCRIBE_PREFIX + video.channelId in keys,
                        )
                    fix.like?.let { apply(video, it) }
                    fix.subscribed?.let { apply(video, it) }
                    Log.i(TAG, "read ${video.id} (#$reads this run): like ${fix.like ?: "same"}, subscribed ${fix.subscribed ?: "same"}")
                }
            }
        }

        private suspend fun apply(
            video: Video,
            rating: LibraryRating,
        ) {
            when (rating) {
                LibraryRating.LIKED -> {
                    likes.likeVideo(
                        LikedVideoInfo(
                            videoId = video.id,
                            title = video.title,
                            thumbnail = video.thumbnailUrl,
                            channelName = video.channelName,
                            isMusic = video.isMusic,
                            channelId = video.channelId,
                            durationSeconds = video.duration,
                        ),
                        mirror = false,
                    )
                }

                LibraryRating.DISLIKED -> {
                    likes.dislikeVideo(video.id, mirror = false)
                }

                LibraryRating.NONE -> {
                    likes.removeLikeState(video.id, mirror = false)
                }
            }
        }

        private suspend fun apply(
            video: Video,
            subscribed: Boolean,
        ) {
            if (subscribed) {
                subscriptions.subscribe(
                    ChannelSubscription(video.channelId, video.channelName, video.channelThumbnailUrl),
                    mirror = false,
                )
            } else {
                subscriptions.unsubscribe(video.channelId, mirror = false)
            }
        }

        private companion object {
            const val TAG = "YouTubeReadThrough"

            /** How long a video stays open before it's read: skipping through videos reads none. */
            const val SETTLE_MS = 3_000L

            /** A video read this recently isn't read again. */
            const val FRESH_MS = 30 * 60_000L
            const val MAX_REMEMBERED = 200
            const val LOAD_FACTOR = 0.75f
            const val MS_PER_MINUTE = 60_000L
        }
    }

/** What the read-through changes in Flow; null leaves that part as it is. */
internal data class ReadThroughFix(
    val like: LibraryRating?,
    val subscribed: Boolean?,
)

/**
 * Compares the watch page with Flow's state. YouTube wins where it said something and Flow has
 * nothing of its own still queued; a page without a like or subscribe control says nothing.
 */
internal fun readThroughFix(
    remoteLike: String?,
    localLike: String?,
    likePending: Boolean,
    remoteSubscribed: Boolean?,
    localSubscribed: Boolean,
    subscriptionPending: Boolean,
): ReadThroughFix {
    val remoteRating =
        when (remoteLike) {
            "LIKE" -> LibraryRating.LIKED
            "DISLIKE" -> LibraryRating.DISLIKED
            "INDIFFERENT" -> LibraryRating.NONE
            else -> null
        }
    val localRating =
        when (localLike) {
            "LIKED" -> LibraryRating.LIKED
            "DISLIKED" -> LibraryRating.DISLIKED
            else -> LibraryRating.NONE
        }
    return ReadThroughFix(
        like = remoteRating?.takeIf { !likePending && it != localRating },
        subscribed = remoteSubscribed?.takeIf { !subscriptionPending && it != localSubscribed },
    )
}
