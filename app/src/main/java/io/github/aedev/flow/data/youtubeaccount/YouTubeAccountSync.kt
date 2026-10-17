package io.github.aedev.flow.data.youtubeaccount

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.ChannelSubscription
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.local.VideoHistoryEntry
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings the active account's YouTube history, subscriptions and library into Flow, so every device that signs
 * in to the same account sees — and the Flow engine learns from — everything watched on any of them.
 * The other half, sending this device's viewing up, is [YouTubeWatchReporter].
 *
 * Only additions travel down: videos and channels Flow does not have yet. Nothing local is deleted or
 * overwritten, and a channel unsubscribed in Flow stays unsubscribed.
 */
@Singleton
class YouTubeAccountSync
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val accounts: YouTubeAccountManager,
        private val store: YouTubeAccountStore,
        private val api: YouTubeAccountApi,
        private val library: YouTubeLibrarySync,
    ) {
        private val lock = Mutex()
        private val viewHistory by lazy { ViewHistory.getInstance(context) }
        private val subscriptions by lazy { SubscriptionRepository.getInstance(context) }

        data class Result(
            val videos: Int,
            val channels: Int,
            val likes: Int = 0,
            val playlists: Int = 0,
        ) {
            val isEmpty: Boolean
                get() = videos == 0 && channels == 0 && likes == 0 && playlists == 0
        }

        /** The background sync: skipped when the last one is recent, light (a few pages) otherwise. */
        suspend fun syncIfStale(): Result? {
            val active = store.current().active ?: return null
            if (System.currentTimeMillis() - store.lastHistoryPull(active.id) < MIN_SYNC_INTERVAL_MS) return null
            val libraryDue = System.currentTimeMillis() - store.lastLibraryPull(active.id) >= LIBRARY_SYNC_INTERVAL_MS
            return sync(historyPages = BACKGROUND_HISTORY_PAGES, includeLibrary = libraryDue)
        }

        /** A full pull, from the settings page: more history, subscriptions and the library. Null in guest mode. */
        suspend fun syncNow(): Result? = sync(historyPages = FULL_HISTORY_PAGES, includeLibrary = true)

        private suspend fun sync(
            historyPages: Int,
            includeLibrary: Boolean,
        ): Result? =
            lock.withLock {
                try {
                    accounts.withActiveAuth { account, auth ->
                        val videos = pullHistory(auth, historyPages)
                        val channels = pullSubscriptions(auth)
                        val now = System.currentTimeMillis()
                        store.setLastHistoryPull(account.id, now)
                        val libraryResult =
                            if (includeLibrary) {
                                library.pull(auth).also { store.setLastLibraryPull(account.id, now) }
                            } else {
                                YouTubeLibrarySync.Result(likes = 0, playlists = 0)
                            }
                        Result(videos, channels, libraryResult.likes, libraryResult.playlists)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    Log.w(TAG, "Account sync failed", e)
                    throw e
                }
            }

        private suspend fun pullHistory(
            auth: YouTubeAccountApi.Auth,
            maxPages: Int,
        ): Int {
            if (PlayerPreferences(context).isWatchHistorySavingBlocked()) return 0
            val fresh = LinkedHashMap<String, RemoteHistoryItem>()
            api.browsePages(auth, HISTORY_BROWSE_ID, maxPages) { page ->
                val items = YouTubeAccountParsers.history(page)
                val known = viewHistory.knownVideoIds(items.map { it.videoId })
                items.filterNot { it.videoId in known }.forEach { fresh.putIfAbsent(it.videoId, it) }
                // Once a whole page is already here, everything older is too: stop paging.
                items.isNotEmpty() && items.any { it.videoId !in known }
            }
            if (fresh.isEmpty()) return 0

            // YouTube lists history newest first without dates; keep that order under the local rows.
            val now = System.currentTimeMillis()
            val entries =
                fresh.values.mapIndexed { index, item ->
                    VideoHistoryEntry(
                        videoId = item.videoId,
                        position = item.durationMs * item.percentWatched / PERCENT,
                        duration = item.durationMs,
                        timestamp = now - index * ORDER_STEP_MS,
                        title = item.title,
                        thumbnailUrl = item.thumbnailUrl,
                        channelName = item.channelName,
                        channelId = item.channelId,
                        isMusic = item.isMusic,
                    )
                }
            viewHistory.bulkSaveHistoryEntries(entries)
            learn(entries)
            return entries.size
        }

        private suspend fun pullSubscriptions(auth: YouTubeAccountApi.Auth): Int {
            val found = LinkedHashMap<String, RemoteChannel>()
            api.browsePages(auth, CHANNELS_BROWSE_ID, SUBSCRIPTION_PAGES) { page ->
                val before = found.size
                YouTubeAccountParsers.channels(page).forEach { found.putIfAbsent(it.channelId, it) }
                found.size > before
            }
            if (found.isEmpty()) return 0
            val skip = subscriptions.getAllSubscriptionIds() + subscriptions.unsubscribedTombstones().keys
            val added =
                found.values
                    .filterNot { it.channelId in skip }
                    .map { ChannelSubscription(channelId = it.channelId, channelName = it.name, channelThumbnail = it.thumbnailUrl) }
            if (added.isEmpty()) return 0
            subscriptions.subscribeAll(added)
            runCatching { FlowNeuroEngine.bootstrapFromSubscriptions(context, added.map { it.channelName }) }
                .onFailure { Log.w(TAG, "Engine could not learn from new subscriptions", it) }
            return added.size
        }

        /** Feeds newly arrived videos to the Flow engine, as a history import does. */
        private suspend fun learn(entries: List<VideoHistoryEntry>) {
            val videos =
                entries.filter { it.title.isNotBlank() }.take(ENGINE_LEARN_LIMIT).map { entry ->
                    Video(
                        id = entry.videoId,
                        title = entry.title,
                        channelName = entry.channelName,
                        channelId = entry.channelId,
                        thumbnailUrl = entry.thumbnailUrl,
                        duration = (entry.duration / MS_PER_SECOND).toInt(),
                        viewCount = 0L,
                        uploadDate = "",
                        timestamp = entry.timestamp,
                    )
                }
            if (videos.isEmpty()) return
            runCatching { FlowNeuroEngine.bootstrapFromWatchHistory(context, videos) }
                .onFailure { Log.w(TAG, "Engine could not learn from synced history", it) }
        }

        private companion object {
            const val TAG = "YouTubeAccountSync"
            const val HISTORY_BROWSE_ID = "FEhistory"

            // The TV app's full channel list; FEsubscriptions is the uploads feed, not the channels.
            const val CHANNELS_BROWSE_ID = "FEchannels"
            const val MIN_SYNC_INTERVAL_MS = 10 * 60_000L
            const val LIBRARY_SYNC_INTERVAL_MS = 6 * 60 * 60_000L
            const val BACKGROUND_HISTORY_PAGES = 3
            const val FULL_HISTORY_PAGES = 25
            const val SUBSCRIPTION_PAGES = 10
            const val ENGINE_LEARN_LIMIT = 500
            const val ORDER_STEP_MS = 1_000L
            const val PERCENT = 100
            const val MS_PER_SECOND = 1000L
        }
    }
