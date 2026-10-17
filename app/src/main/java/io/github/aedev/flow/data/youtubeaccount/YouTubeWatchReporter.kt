package io.github.aedev.flow.data.youtubeaccount

import android.util.Log
import io.github.aedev.flow.data.local.PlaybackProgressListener
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
                    val urls = api.trackingUrls(auth, videoId) ?: return@withActiveAuth
                    val started = Session(videoId, account.id, urls, newCpn(), lastPositionMs = positionMs, lastPingAt = now)
                    api.ping(auth, urls.playbackUrl, started.params(positionMs, durationMs))
                    session = started
                    return@withActiveAuth
                }
                val open = current ?: return@withActiveAuth
                val watchtimeUrl = open.urls.watchtimeUrl ?: return@withActiveAuth
                if (now - open.lastPingAt < MIN_PING_INTERVAL_MS && positionMs < durationMs) return@withActiveAuth
                val params =
                    open.params(positionMs, durationMs) +
                        mapOf(
                            "st" to statsSeconds(open.lastPositionMs),
                            "et" to statsSeconds(positionMs),
                            "state" to "playing",
                        )
                api.ping(auth, watchtimeUrl, params)
                session = open.copy(lastPositionMs = positionMs, lastPingAt = now)
            }
        }

        private data class Session(
            val videoId: String,
            val accountId: String,
            val urls: PlaybackTrackingUrls,
            val cpn: String,
            val lastPositionMs: Long,
            val lastPingAt: Long,
        ) {
            fun params(
                positionMs: Long,
                durationMs: Long,
            ) = mapOf(
                "ver" to "2",
                "cpn" to cpn,
                "cmt" to statsSeconds(positionMs),
                "len" to statsSeconds(durationMs),
            )
        }

        private companion object {
            const val TAG = "YouTubeWatchReporter"

            /** A video picked up again after this long counts as a new viewing, as on YouTube. */
            const val SESSION_GAP_MS = 30 * 60_000L

            /** Flow saves every 10 s; YouTube's own clients ping watch time far less often. */
            const val MIN_PING_INTERVAL_MS = 20_000L
        }
    }

private const val CPN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
private const val CPN_LENGTH = 16

/** A fresh client playback nonce: the id YouTube groups one viewing's stats pings under. */
internal fun newCpn(): String = (1..CPN_LENGTH).map { CPN_ALPHABET[Random.nextInt(CPN_ALPHABET.length)] }.joinToString("")

/** Milliseconds as the seconds string the stats endpoints take, such as `12.345`. */
internal fun statsSeconds(ms: Long): String = String.format(Locale.US, "%.3f", ms / 1000.0)
