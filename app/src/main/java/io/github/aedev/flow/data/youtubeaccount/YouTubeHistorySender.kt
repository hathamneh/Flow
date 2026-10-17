package io.github.aedev.flow.data.youtubeaccount

import io.github.aedev.flow.utils.cipher.PipePipeNsigDecoder
import javax.inject.Inject
import javax.inject.Singleton

/** Sends the outbox's history changes: watch records, removals, clearing and pausing. */
@Singleton
class YouTubeHistorySender
    @Inject
    constructor(
        private val api: YouTubeAccountApi,
    ) {
        suspend fun send(
            auth: YouTubeAccountApi.Auth,
            mutation: YouTubeMutation,
        ) {
            when (mutation) {
                is YouTubeMutation.HistoryWatch -> {
                    recordWatch(auth, mutation.videoId, mutation.positionMs, mutation.durationMs)
                }

                is YouTubeMutation.HistoryRemove -> {
                    // Not on the first history pages: nothing YouTube shows to remove.
                    val token = api.historyFeedbackToken(auth, mutation.videoId) ?: return
                    api.feedback(auth, listOf(token))
                }

                YouTubeMutation.ClearHistory -> {
                    api.clearWatchHistory(auth)
                }

                is YouTubeMutation.PauseHistory -> {
                    api.setWatchHistoryPaused(auth, mutation.paused)
                }

                else -> {
                    error("Not a history change: ${mutation.key}")
                }
            }
        }

        /**
         * A whole watch record at once: the opening ping, then watch time from the start to [positionMs].
         * The pair is what shows the watched bar on YouTube (diagnostics run 17).
         */
        private suspend fun recordWatch(
            auth: YouTubeAccountApi.Auth,
            videoId: String,
            positionMs: Long,
            durationMs: Long,
        ) {
            val urls = api.trackingUrls(auth, videoId, PipePipeNsigDecoder.signatureTimestamp()) ?: return
            val base = watchParams(newCpn(), positionMs, durationMs)
            api.ping(auth, urls.playbackUrl, base)
            val watchtime = urls.watchtimeUrl ?: return
            api.ping(auth, watchtime, base + watchtimeRange(0L, positionMs))
        }
    }

/** The parameters every stats ping of one viewing carries. */
internal fun watchParams(
    cpn: String,
    positionMs: Long,
    durationMs: Long,
) = mapOf(
    "ver" to "2",
    "cpn" to cpn,
    "cmt" to statsSeconds(positionMs),
    "len" to statsSeconds(durationMs),
)

/** The stretch a watch-time ping reports as played. */
internal fun watchtimeRange(
    fromMs: Long,
    toMs: Long,
) = mapOf("st" to statsSeconds(fromMs), "et" to statsSeconds(toMs), "state" to "playing")
