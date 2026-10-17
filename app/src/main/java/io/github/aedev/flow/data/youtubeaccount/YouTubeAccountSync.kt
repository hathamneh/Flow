package io.github.aedev.flow.data.youtubeaccount

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.VideoHistoryEntry
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.recommendation.FlowNeuroEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings the active account's YouTube history, subscriptions and library into Flow, so every device that signs
 * in to the same account sees — and the Flow engine learns from — everything watched on any of them.
 * The other half, sending this device's viewing up, is [YouTubeWatchReporter].
 *
 * History only gains videos Flow doesn't have yet. Subscriptions, likes and Watch later follow YouTube
 * both ways, without undoing what was changed in Flow ([YouTubeLibraryMerge]); Flow's own changes go up
 * through the [YouTubeOutbox].
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
        private val merge: YouTubeLibraryMerge,
        private val snapshots: YouTubeRemoteSnapshot,
    ) {
        private val lock = Mutex()
        private val _progress = MutableStateFlow<SyncProgress?>(null)

        /** The sync in progress, or null when none is running. */
        val progress: StateFlow<SyncProgress?> = _progress.asStateFlow()
        private val viewHistory by lazy { ViewHistory.getInstance(context) }

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
            // A sync already running (a sign-in's or a manual one) covers this request.
            if (lock.isLocked) return null
            return sync(historyPages = BACKGROUND_HISTORY_PAGES, includeLibrary = false, onlyIfStale = true)
        }

        /** A full pull, from the settings page: more history, subscriptions and the library. Null in guest mode. */
        suspend fun syncNow(): Result? = sync(historyPages = FULL_HISTORY_PAGES, includeLibrary = true)

        private suspend fun sync(
            historyPages: Int,
            includeLibrary: Boolean,
            onlyIfStale: Boolean = false,
        ): Result? =
            lock.withLock {
                try {
                    var pages = historyPages
                    var withLibrary = includeLibrary
                    var firstImport = false
                    if (onlyIfStale) {
                        // Read under the lock, so a request queued behind a sync sees the pull it just made.
                        val active = store.current().active ?: return@withLock null
                        val lastPull = store.lastHistoryPull(active.id)
                        if (System.currentTimeMillis() - lastPull < MIN_SYNC_INTERVAL_MS) return@withLock null
                        // A profile's first sync is its import from YouTube: as much as a manual sync pulls.
                        firstImport = lastPull == 0L
                        if (firstImport) pages = FULL_HISTORY_PAGES
                        withLibrary = firstImport ||
                            System.currentTimeMillis() - store.lastLibraryPull(active.id) >= LIBRARY_SYNC_INTERVAL_MS
                    }
                    accounts.withActiveAuth { account, auth ->
                        // Started by hand right after a sign-in, a sync is that profile's import all the same.
                        if (!firstImport) firstImport = store.lastHistoryPull(account.id) == 0L

                        fun step(phase: SyncProgress.Phase) {
                            _progress.value = SyncProgress(account.name, phase, firstImport)
                        }
                        // Subscriptions first: a handful of requests that fill the Subscriptions feed right away.
                        step(SyncProgress.Phase.SUBSCRIPTIONS)
                        val channels = pullSubscriptions(auth, account.id)
                        step(SyncProgress.Phase.HISTORY)
                        val videos = pullHistory(auth, account.id, pages)
                        val now = System.currentTimeMillis()
                        store.setLastHistoryPull(account.id, now)
                        val libraryResult =
                            if (withLibrary) {
                                step(SyncProgress.Phase.LIBRARY)
                                library.pull(auth, account.id).also { store.setLastLibraryPull(account.id, now) }
                            } else {
                                YouTubeLibrarySync.Result(likes = library.pullRecent(auth, account.id), playlists = 0)
                            }
                        Result(videos, channels, libraryResult.likes, libraryResult.playlists)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    Log.w(TAG, "Account sync failed", e)
                    throw e
                } finally {
                    _progress.value = null
                }
            }

        private suspend fun pullHistory(
            auth: YouTubeAccountApi.Auth,
            accountId: String,
            maxPages: Int,
        ): Int {
            if (PlayerPreferences(context).isWatchHistorySavingBlocked()) return 0
            // Cleared in Flow only: mark where YouTube's history stands now and bring none of it back.
            if (snapshots.get(accountId, YouTubeRemoteSnapshot.HISTORY_FLOOR_PENDING) != null) {
                val newest = YouTubeAccountParsers.history(api.browse(auth, browseId = HISTORY_BROWSE_ID)).map { it.videoId }
                snapshots.put(accountId, YouTubeRemoteSnapshot.HISTORY_FLOOR, newest.take(FLOOR_SIZE))
                snapshots.drop(accountId, YouTubeRemoteSnapshot.HISTORY_FLOOR_PENDING)
                return 0
            }
            val floor =
                snapshots
                    .get(accountId, YouTubeRemoteSnapshot.HISTORY_FLOOR)
                    ?.ids
                    .orEmpty()
                    .toHashSet()
            val removed =
                snapshots
                    .get(accountId, YouTubeRemoteSnapshot.HISTORY_REMOVED)
                    ?.ids
                    .orEmpty()
                    .toHashSet()
            val fresh = LinkedHashMap<String, RemoteHistoryItem>()
            api.browsePages(auth, HISTORY_BROWSE_ID, maxPages) { page ->
                val all = YouTubeAccountParsers.history(page)
                val window = historyWindow(all.map { it.videoId }, floor, removed)
                val items = all.filter { it.videoId in window.ids }
                val known = viewHistory.knownVideoIds(items.map { it.videoId })
                items.filterNot { it.videoId in known }.forEach { fresh.putIfAbsent(it.videoId, it) }
                // Once a whole page is already here, everything older is too: stop paging.
                !window.reachedFloor && items.isNotEmpty() && items.any { it.videoId !in known }
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

        private suspend fun pullSubscriptions(
            auth: YouTubeAccountApi.Auth,
            accountId: String,
        ): Int {
            val added = merge.subscriptions(auth, accountId, SUBSCRIPTION_PAGES)
            if (added.isEmpty()) return 0
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
            // In small batches: each holds the engine's lock, which the home feed waits on to rank.
            videos.chunked(ENGINE_LEARN_BATCH).forEach { batch ->
                runCatching { FlowNeuroEngine.bootstrapFromWatchHistory(context, batch) }
                    .onFailure { Log.w(TAG, "Engine could not learn from synced history", it) }
                yield()
            }
        }

        private companion object {
            const val TAG = "YouTubeAccountSync"
            const val HISTORY_BROWSE_ID = "FEhistory"

            /** How many of YouTube's newest history entries mark a Flow-only clear. */
            const val FLOOR_SIZE = 20

            const val MIN_SYNC_INTERVAL_MS = 10 * 60_000L
            const val LIBRARY_SYNC_INTERVAL_MS = 6 * 60 * 60_000L
            const val BACKGROUND_HISTORY_PAGES = 3
            const val FULL_HISTORY_PAGES = 25
            const val SUBSCRIPTION_PAGES = 10
            const val ENGINE_LEARN_LIMIT = 500
            const val ENGINE_LEARN_BATCH = 50
            const val ORDER_STEP_MS = 1_000L
            const val PERCENT = 100
            const val MS_PER_SECOND = 1000L
        }
    }

/** The part of a history page the pull may import, and whether it reached the mark of a Flow-only clear. */
internal data class HistoryWindow(
    val ids: Set<String>,
    val reachedFloor: Boolean,
)

/**
 * Reads one page of YouTube's history ([page], newest first) for import: everything before the first
 * [floor] entry (what YouTube had when history was cleared in Flow), less what was [removed] in Flow.
 */
internal fun historyWindow(
    page: List<String>,
    floor: Set<String>,
    removed: Set<String>,
): HistoryWindow {
    val end = page.indexOfFirst { it in floor }
    val before = if (end < 0) page else page.subList(0, end)
    return HistoryWindow(before.filterNot { it in removed }.toSet(), reachedFloor = end >= 0)
}
