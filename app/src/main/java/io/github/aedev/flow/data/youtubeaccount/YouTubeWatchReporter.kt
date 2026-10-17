package io.github.aedev.flow.data.youtubeaccount

import android.util.Log
import io.github.aedev.flow.data.local.LibraryChange
import io.github.aedev.flow.data.local.LibraryMirror
import io.github.aedev.flow.data.local.PlaybackProgressListener
import io.github.aedev.flow.utils.cipher.PipePipeNsigDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Puts what is watched in Flow into the active account's YouTube history, the way the TV app does: the
 * first progress save of a video opens a watch record (`/api/stats/playback`), later saves extend it
 * (`/api/stats/watchtime`). It rides on Flow's existing progress saves — every 10 s while playing — so
 * it adds no timers of its own, and it is silent in guest mode or with the option off.
 */
@Singleton
class YouTubeWatchReporter
    @Inject
    constructor(
        private val accounts: YouTubeAccountManager,
        private val api: YouTubeAccountApi,
    ) : PlaybackProgressListener {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val lock = Mutex()
        private var session: Session? = null

        override fun onPlaybackProgress(
            videoId: String,
            positionMs: Long,
            durationMs: Long,
        ) = enqueue(videoId, positionMs, durationMs, mayOpen = true)

        /**
         * The end of a music listen: extends the watch record the track's start opened, so YouTube
         * learns how much was heard. Never opens one, so a start Flow kept out of history stays out.
         */
        fun onListenFinished(
            videoId: String,
            playedMs: Long,
            durationMs: Long,
        ) = enqueue(videoId, playedMs, durationMs, mayOpen = false)

        private fun enqueue(
            videoId: String,
            positionMs: Long,
            durationMs: Long,
            mayOpen: Boolean,
        ) {
            if (durationMs <= 0L || positionMs < 0L) return
            scope.launch {
                try {
                    lock.withLock { report(videoId, positionMs.coerceAtMost(durationMs), durationMs, mayOpen) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // History reporting is best effort and must never disturb playback.
                    Log.w(TAG, "Could not report $videoId to YouTube history", e)
                }
            }
        }

        private suspend fun report(
            videoId: String,
            positionMs: Long,
            durationMs: Long,
            mayOpen: Boolean,
        ) {
            if (!accounts.state.first().reportHistory) return
            accounts.withActiveAuth { account, auth ->
                val now = System.currentTimeMillis()
                val current = session
                val sameSession =
                    current != null &&
                        current.videoId == videoId &&
                        current.accountId == account.id &&
                        now - current.lastPingAt < SESSION_GAP_MS
                if (!sameSession) {
                    if (!mayOpen) return@withActiveAuth
                    try {
                        open(auth, account.id, videoId, positionMs, durationMs, now)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Offline or YouTube busy: the outbox sends the whole record once it can.
                        if (SendOutcome.of(e) != SendOutcome.RETRY) throw e
                        LibraryMirror.report(LibraryChange.HistoryWatched(videoId, positionMs, durationMs))
                    }
                    return@withActiveAuth
                }
                val active = current ?: return@withActiveAuth
                val watchtimeUrl = active.urls.watchtimeUrl ?: return@withActiveAuth
                if (now - active.lastPingAt < MIN_PING_INTERVAL_MS && positionMs < durationMs) return@withActiveAuth
                api.ping(
                    auth,
                    watchtimeUrl,
                    watchParams(active.cpn, positionMs, durationMs) + watchtimeRange(active.lastPositionMs, positionMs),
                )
                session = active.copy(lastPositionMs = positionMs, lastPingAt = now)
            }
        }

        /**
         * Opens a watch record. One that starts at the end, as "Mark as watched" does, also reports the
         * whole video as played, since no later progress will.
         */
        private suspend fun open(
            auth: YouTubeAccountApi.Auth,
            accountId: String,
            videoId: String,
            positionMs: Long,
            durationMs: Long,
            now: Long,
        ) {
            val urls = api.trackingUrls(auth, videoId, PipePipeNsigDecoder.signatureTimestamp()) ?: return
            val started = Session(videoId, accountId, urls, newCpn(), lastPositionMs = positionMs, lastPingAt = now)
            api.ping(auth, urls.playbackUrl, watchParams(started.cpn, positionMs, durationMs))
            session = started
            val watchtime = urls.watchtimeUrl
            if (watchtime != null && positionMs >= durationMs * WATCHED_SHARE) {
                api.ping(auth, watchtime, watchParams(started.cpn, positionMs, durationMs) + watchtimeRange(0L, positionMs))
            }
        }

        private data class Session(
            val videoId: String,
            val accountId: String,
            val urls: PlaybackTrackingUrls,
            val cpn: String,
            val lastPositionMs: Long,
            val lastPingAt: Long,
        )

        private companion object {
            const val TAG = "YouTubeWatchReporter"

            /** A video picked up again after this long counts as a new viewing, as on YouTube. */
            const val SESSION_GAP_MS = 30 * 60_000L

            /** Flow saves every 10 s; YouTube's own clients ping watch time far less often. */
            const val MIN_PING_INTERVAL_MS = 20_000L

            /** Opened this close to the end, a viewing counts as finished. */
            const val WATCHED_SHARE = 0.95
        }
    }

private const val CPN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
private const val CPN_LENGTH = 16

/** A fresh client playback nonce: the id YouTube groups one viewing's stats pings under. */
internal fun newCpn(): String = (1..CPN_LENGTH).map { CPN_ALPHABET[Random.nextInt(CPN_ALPHABET.length)] }.joinToString("")

/** Milliseconds as the seconds string the stats endpoints take, such as `12.345`. */
internal fun statsSeconds(ms: Long): String = String.format(Locale.US, "%.3f", ms / 1000.0)
