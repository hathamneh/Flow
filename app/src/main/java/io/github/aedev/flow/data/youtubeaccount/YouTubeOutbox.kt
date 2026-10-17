package io.github.aedev.flow.data.youtubeaccount

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.LibraryChange
import io.github.aedev.flow.data.local.LibraryMirror
import io.github.aedev.flow.data.local.LibraryRating
import io.github.aedev.flow.data.local.dao.YouTubeOutboxDao
import io.github.aedev.flow.data.local.entity.YouTubeOutboxEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends the viewer's own library changes to their YouTube account. A change is saved locally first
 * (the screen never waits on YouTube), queued here under its key so a newer change to the same thing
 * replaces it, and sent by [YouTubeOutboxWorker] once there's a connection.
 *
 * Sends and the pull of the same lists take turns ([exclusive]), so a pull never reads a list halfway
 * through a send and mistakes Flow's own change for one made on YouTube.
 */
@Singleton
class YouTubeOutbox
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dao: YouTubeOutboxDao,
        private val store: YouTubeAccountStore,
        private val accounts: YouTubeAccountManager,
        private val api: YouTubeAccountApi,
        private val snapshots: YouTubeRemoteSnapshot,
        private val playlistSender: YouTubePlaylistSender,
        private val historySender: YouTubeHistorySender,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // One reader keeps changes in the order they were made, so the last one per key wins.
        private val changes = Channel<LibraryChange>(Channel.UNLIMITED)
        private val turn = Mutex()

        /** What the account page shows: changes still waiting and changes YouTube refused. */
        data class Status(
            val waiting: Int,
            val rejected: Int,
        )

        /** Starts listening to the library. Called once, when the app starts. */
        fun start() {
            LibraryMirror.listener = { changes.trySend(it) }
            scope.launch {
                for (change in changes) {
                    runCatching { enqueue(change) }.onFailure { Log.w(TAG, "Could not queue a change for YouTube", it) }
                }
            }
            // At start, and whenever an account is signed in (again): sends what was left queued.
            scope.launch {
                store.state
                    .map { state -> state.active?.takeUnless { it.needsSignIn }?.id }
                    .distinctUntilChanged()
                    .filterNotNull()
                    .collect { YouTubeOutboxWorker.enqueue(context, START_DELAY_S) }
            }
        }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        fun status(): Flow<Status> =
            store.state.map { it.active?.id }.flatMapLatest { id ->
                if (id == null) flowOf(Status(0, 0)) else combine(dao.observeCount(id), snapshots.rejectedCount(id), ::Status)
            }

        suspend fun clearRejected() {
            store.current().active?.let { snapshots.clearRejected(it.id) }
        }

        /** The keys queued for [accountId], which a pull leaves alone. */
        suspend fun pendingKeys(accountId: String): Set<String> = dao.keys(accountId).toHashSet()

        /** Runs [block] while no change is being sent. */
        suspend fun <T> exclusive(block: suspend () -> T): T = turn.withLock { block() }

        private suspend fun enqueue(change: LibraryChange) {
            // Signed out, Flow is local only: nothing waits to be sent on the next sign-in.
            val state = store.current()
            val account = state.active ?: return
            // With history uploads off, Flow leaves the account's history alone altogether.
            if (change.isHistory() && !state.reportHistory) return
            val now = System.currentTimeMillis()
            when (change) {
                is LibraryChange.PlaylistDeleted -> {
                    snapshots.addDeletedPlaylist(account.id, change.remoteId)
                }

                is LibraryChange.PlaylistSaved -> {
                    if (change.saved) snapshots.undelete(account.id, change.playlistId)
                }

                is LibraryChange.HistoryRemoved -> {
                    snapshots.addHistoryRemoved(account.id, change.videoId)
                }

                is LibraryChange.HistoryRestored -> {
                    dao.delete(account.id, YouTubeMutation.HISTORY_PREFIX + change.videoId)
                    snapshots.forgetHistoryRemoved(account.id, change.videoId)
                }

                is LibraryChange.HistoryCleared -> {
                    // Watch records and removals still queued are moot now.
                    dao.keys(account.id).filter { it.startsWith(YouTubeMutation.HISTORY_PREFIX) }.forEach { dao.delete(account.id, it) }
                    if (!change.alsoOnYouTube) snapshots.put(account.id, YouTubeRemoteSnapshot.HISTORY_FLOOR_PENDING, listOf(PENDING))
                }

                else -> {
                    Unit
                }
            }
            YouTubeMutation.from(change).forEach { mutation ->
                if (alreadyOnYouTube(account.id, mutation)) {
                    dao.delete(account.id, mutation.key)
                } else {
                    dao.upsert(YouTubeOutboxEntity(account.id, mutation.key, YouTubeMutation.encode(mutation), now))
                }
            }
            YouTubeOutboxWorker.enqueue(context)
        }

        /**
         * True when the last pull already showed the state [mutation] asks for, as when a like is
         * undone and redone. Only additions are trusted: YouTube's lists don't show dislikes, so a
         * cleared rating or a removal is always sent.
         */
        private suspend fun alreadyOnYouTube(
            accountId: String,
            mutation: YouTubeMutation,
        ): Boolean =
            when (mutation) {
                is YouTubeMutation.Rate -> {
                    mutation.rating == LibraryRating.LIKED &&
                        snapshots.get(accountId, RemoteCollection.LIKES)?.ids?.contains(mutation.videoId) == true
                }

                is YouTubeMutation.Subscribe -> {
                    mutation.on && snapshots.get(accountId, RemoteCollection.SUBSCRIPTIONS)?.ids?.contains(mutation.channelId) == true
                }

                is YouTubeMutation.PlaylistItem -> {
                    mutation.add && snapshots.get(accountId, snapshotOf(mutation.playlistId))?.ids?.contains(mutation.videoId) == true
                }

                is YouTubeMutation.PlaylistSave -> {
                    mutation.saved && snapshots.get(accountId, YouTubeRemoteSnapshot.PLAYLISTS)?.ids?.contains(mutation.playlistId) == true
                }

                is YouTubeMutation.PlaylistOrder,
                is YouTubeMutation.PlaylistRename,
                is YouTubeMutation.HistoryWatch,
                is YouTubeMutation.HistoryRemove,
                YouTubeMutation.ClearHistory,
                is YouTubeMutation.PauseHistory,
                -> {
                    false
                }
            }

        /** One round of sending. */
        suspend fun drain(): DrainResult {
            val state = store.current()
            dao.deleteOtherAccounts(state.accounts.map { it.id })
            val account = state.active ?: return DrainResult.DONE
            // Kept queued until the account is signed in again; the sign-in starts a new round.
            if (account.needsSignIn) return DrainResult.DONE
            val batch = dao.oldest(account.id, BATCH_SIZE)
            if (batch.isEmpty()) return DrainResult.DONE
            // A history removal waits out the undo window; a later round sends it.
            val now = System.currentTimeMillis()
            val (held, due) = batch.partition { it.isHistoryRemoval() && now - it.enqueuedAt < UNDO_WINDOW_MS }
            held.minOfOrNull { it.enqueuedAt }?.let { oldest ->
                YouTubeOutboxWorker.enqueue(context, (UNDO_WINDOW_MS - (now - oldest)) / MS_PER_SECOND + 1)
            }
            var rejected = 0
            try {
                due.forEachIndexed { index, entry ->
                    if (index > 0) delay(PACE_MS)
                    when (turn.withLock { send(account.id, entry) }) {
                        SendOutcome.SENT -> {
                            Unit
                        }

                        SendOutcome.REJECTED -> {
                            rejected++
                        }

                        SendOutcome.RETRY, SendOutcome.SIGNED_OUT -> {
                            // A week of trying is enough: the change stays in Flow and counts as couldn't sync.
                            if (System.currentTimeMillis() - entry.enqueuedAt <= GIVE_UP_MS) return DrainResult.RETRY
                            dao.deleteIfUnchanged(account.id, entry.key, entry.enqueuedAt, entry.payload)
                            rejected++
                        }
                    }
                }
            } finally {
                snapshots.addRejected(account.id, rejected)
            }
            return if (batch.size == BATCH_SIZE && due.isNotEmpty()) DrainResult.MORE else DrainResult.DONE
        }

        private suspend fun send(
            accountId: String,
            entry: YouTubeOutboxEntity,
        ): SendOutcome {
            val mutation = YouTubeMutation.decode(entry.payload)
            if (mutation == null) {
                dao.deleteIfUnchanged(accountId, entry.key, entry.enqueuedAt, entry.payload)
                return SendOutcome.REJECTED
            }
            var error: Exception? = null
            val sent =
                try {
                    accounts.withActiveAuth { account, auth ->
                        // The active account changed since this round started: leave the change for its own account.
                        if (account.id != accountId) return@withActiveAuth false
                        apply(auth, mutation)
                        true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e
                    null
                }
            // No token right now (offline refresh, or signed out), or another account is active: keep it.
            val outcome = if (error == null && sent != true) SendOutcome.SIGNED_OUT else SendOutcome.of(error)
            Log.i(TAG, "${entry.key} ${outcome.name}${error?.let { " ($it)" }.orEmpty()}")
            when (outcome) {
                SendOutcome.SENT -> {
                    dao.deleteIfUnchanged(accountId, entry.key, entry.enqueuedAt, entry.payload)
                    remember(accountId, mutation)
                }

                SendOutcome.REJECTED -> {
                    dao.deleteIfUnchanged(accountId, entry.key, entry.enqueuedAt, entry.payload)
                }

                SendOutcome.RETRY, SendOutcome.SIGNED_OUT -> {
                    dao.recordFailure(accountId, entry.key, entry.enqueuedAt, (error?.toString() ?: outcome.name).take(ERROR_LENGTH))
                }
            }
            return outcome
        }

        private suspend fun apply(
            auth: YouTubeAccountApi.Auth,
            mutation: YouTubeMutation,
        ) {
            when (mutation) {
                is YouTubeMutation.Rate -> {
                    api.rate(
                        auth,
                        mutation.videoId,
                        when (mutation.rating) {
                            LibraryRating.LIKED -> LikeAction.LIKE

                            LibraryRating.DISLIKED -> LikeAction.DISLIKE

                            // removelike clears a dislike too; removedislike doesn't exist (404).
                            LibraryRating.NONE -> LikeAction.REMOVE
                        },
                    )
                }

                is YouTubeMutation.Subscribe -> {
                    api.setSubscribed(auth, mutation.channelId, mutation.on)
                }

                is YouTubeMutation.PlaylistItem,
                is YouTubeMutation.PlaylistOrder,
                is YouTubeMutation.PlaylistRename,
                is YouTubeMutation.PlaylistSave,
                -> {
                    playlistSender.send(auth, mutation)
                }

                is YouTubeMutation.HistoryWatch,
                is YouTubeMutation.HistoryRemove,
                YouTubeMutation.ClearHistory,
                is YouTubeMutation.PauseHistory,
                -> {
                    historySender.send(auth, mutation)
                }
            }
        }

        /**
         * After Flow removed something on YouTube, the snapshot forgets it, so the next pull doesn't
         * read its absence as YouTube's own removal. Additions wait for the pull to see them: YouTube
         * decides which lists a like lands in (Liked videos, Liked music).
         */
        private suspend fun remember(
            accountId: String,
            mutation: YouTubeMutation,
        ) {
            when (mutation) {
                is YouTubeMutation.Rate -> {
                    if (mutation.rating != LibraryRating.LIKED) {
                        snapshots.forget(accountId, mutation.videoId, RemoteCollection.LIKES, RemoteCollection.MUSIC_LIKES)
                    }
                }

                is YouTubeMutation.Subscribe -> {
                    if (!mutation.on) snapshots.forget(accountId, mutation.channelId, RemoteCollection.SUBSCRIPTIONS)
                }

                is YouTubeMutation.PlaylistItem -> {
                    if (!mutation.add) snapshots.forget(accountId, mutation.videoId, listOf(snapshotOf(mutation.playlistId)))
                }

                is YouTubeMutation.PlaylistSave -> {
                    if (!mutation.saved) snapshots.forget(accountId, mutation.playlistId, listOf(YouTubeRemoteSnapshot.PLAYLISTS))
                }

                YouTubeMutation.ClearHistory -> {
                    snapshots.drop(accountId, YouTubeRemoteSnapshot.HISTORY_REMOVED)
                    snapshots.drop(accountId, YouTubeRemoteSnapshot.HISTORY_FLOOR)
                }

                is YouTubeMutation.PlaylistOrder,
                is YouTubeMutation.PlaylistRename,
                is YouTubeMutation.HistoryWatch,
                is YouTubeMutation.HistoryRemove,
                is YouTubeMutation.PauseHistory,
                -> {
                    Unit
                }
            }
        }

        private fun LibraryChange.isHistory() =
            this is LibraryChange.HistoryWatched ||
                this is LibraryChange.HistoryRemoved ||
                this is LibraryChange.HistoryRestored ||
                this is LibraryChange.HistoryCleared ||
                this is LibraryChange.HistoryPaused

        private fun YouTubeOutboxEntity.isHistoryRemoval() =
            key.startsWith(YouTubeMutation.HISTORY_PREFIX) && YouTubeMutation.decode(payload) is YouTubeMutation.HistoryRemove

        /** The snapshot holding a playlist's videos: Watch later's own, or an owned playlist's. */
        private fun snapshotOf(playlistId: String) =
            if (playlistId ==
                YouTubeMutation.WATCH_LATER
            ) {
                RemoteCollection.WATCH_LATER.id
            } else {
                YouTubeRemoteSnapshot.playlistList(playlistId)
            }

        /** Whether the worker is done, should run another round now, or should back off. */
        enum class DrainResult { DONE, MORE, RETRY }

        private companion object {
            const val TAG = "YouTubeOutbox"
            const val BATCH_SIZE = 50
            const val PACE_MS = 300L
            const val GIVE_UP_MS = 7 * 24 * 60 * 60_000L
            const val ERROR_LENGTH = 200
            const val UNDO_WINDOW_MS = 10_000L
            const val MS_PER_SECOND = 1000L
            const val PENDING = "pending"

            // As the account sync waits: a job bound during startup competes with it on slow TVs.
            const val START_DELAY_S = 10L
        }
    }
