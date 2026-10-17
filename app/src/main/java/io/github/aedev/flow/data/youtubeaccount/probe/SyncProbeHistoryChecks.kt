package io.github.aedev.flow.data.youtubeaccount.probe

import io.github.aedev.flow.R
import io.github.aedev.flow.data.youtubeaccount.AccountClient
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccountApi
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccountParsers
import io.github.aedev.flow.data.youtubeaccount.browsePages
import io.github.aedev.flow.data.youtubeaccount.newCpn
import io.github.aedev.flow.data.youtubeaccount.objectsWithAnyKey
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.HISTORY_PAUSE
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.HISTORY_REMOVE
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.HISTORY_RESUME
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.WATCHED_PING
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.WEB_REMIX
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeSession.Companion.HISTORY
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeSession.Companion.HISTORY_VIDEOS
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeSession.Companion.LIST_PAGES
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeSession.Companion.UNKNOWN
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeSession.Companion.VIDEO
import io.github.aedev.flow.data.youtubeaccount.statsSeconds
import io.github.aedev.flow.utils.cipher.PipePipeNsigDecoder

private const val HISTORY_READ_ATTEMPTS = 8
private const val PRIVACY_PAGE = "SPaccount_privacy"
private const val MUSIC_LIKES_PAGE = "FEmusic_liked_videos"
private const val HTTP_BAD_REQUEST = 400
private const val OPEN_POSITION_MS = 5_000L
private const val MS_PER_SECOND = 1000L
private const val FALLBACK_LENGTH_S = 600L

/** Renderers a feed shows in place of, or above, its items; used to spot the "history is paused" notice. */
private val NOTICE_KEYS = listOf("messageRenderer", "backgroundPromoRenderer", "feedNudgeRenderer", "alertWithButtonRenderer")

private fun pingParams(
    cpn: String,
    positionMs: Long,
    lengthMs: Long,
) = mapOf(
    "ns" to "yt",
    "ver" to "2",
    "cpn" to cpn,
    "cmt" to statsSeconds(positionMs),
    "len" to statsSeconds(lengthMs),
)

/** The "mark as watched" every client fakes: a watch record opened and closed at the very end. */
internal suspend fun SyncProbeSession.probeWatchedPing() {
    running(WATCHED_PING)
    val urls = call { api.player(it, VIDEO, PipePipeNsigDecoder.signatureTimestamp()) }
    if (!urls.ok) return fail(WATCHED_PING, urls)
    val tracking = urls.value?.let(YouTubeAccountParsers::trackingUrls)
    val watchtimeUrl = tracking?.watchtimeUrl
    val lengthS = tracking?.lengthSeconds
    if (tracking == null || watchtimeUrl == null || lengthS == null) {
        val missing =
            if (tracking == null) {
                listOf("playbackTracking (${urls.value?.let(YouTubeAccountParsers::playability) ?: UNKNOWN})")
            } else {
                listOfNotNull(
                    "videostatsWatchtimeUrl".takeIf { watchtimeUrl == null },
                    "videoDetails.lengthSeconds".takeIf {
                        lengthS ==
                            null
                    },
                )
            }
        return report(
            WATCHED_PING,
            SyncProbeStatus.FAIL,
            urls.http,
            listOf(SyncProbeNote(R.string.yt_probe_note_no_tracking, missing.joinToString())),
        )
    }
    val lengthMs = lengthS * MS_PER_SECOND
    val params = pingParams(newCpn(), lengthMs, lengthMs) + ("final" to "1")
    val open = call { api.ping(it, tracking.playbackUrl, params) }
    if (!open.ok) return fail(WATCHED_PING, open)
    pace()
    val end = statsSeconds(lengthMs)
    val watch = call { api.ping(it, watchtimeUrl, params + mapOf("st" to end, "et" to end)) }
    if (!watch.ok) return fail(WATCHED_PING, watch)
    report(
        WATCHED_PING,
        SyncProbeStatus.MANUAL,
        watch.value,
        listOf(
            SyncProbeNote(R.string.yt_probe_note_pings, open.value ?: 0, watch.value ?: 0),
            SyncProbeNote(R.string.yt_probe_note_check_watched, VIDEO),
        ),
    )
}

/** The video ids on the first history pages. */
private suspend fun SyncProbeSession.historyIds(auth: YouTubeAccountApi.Auth): Set<String> {
    val found = LinkedHashSet<String>()
    api.browsePages(auth, HISTORY, LIST_PAGES) { page ->
        val before = found.size
        YouTubeAccountParsers.history(page).forEach { found += it.videoId }
        found.size > before
    }
    return found
}

/**
 * Puts a video in history with an opening ping, then takes it out again with its feedback token. The
 * video is one not already in the account's history, so nothing the person watched is removed.
 */
internal suspend fun SyncProbeSession.probeHistoryRemove() {
    running(HISTORY_REMOVE)
    val before = call { historyIds(it) }
    val seen = before.value ?: return fail(HISTORY_REMOVE, before)
    val videoId =
        HISTORY_VIDEOS.firstOrNull { it !in seen }
            ?: return skip(HISTORY_REMOVE, SyncProbeNote(R.string.yt_probe_note_already, HISTORY))
    val urls = call { api.player(it, videoId, PipePipeNsigDecoder.signatureTimestamp()) }
    if (!urls.ok) return fail(HISTORY_REMOVE, urls)
    val playability = urls.value?.let(YouTubeAccountParsers::playability) ?: UNKNOWN
    val tracking =
        urls.value?.let(YouTubeAccountParsers::trackingUrls)
            ?: return report(
                HISTORY_REMOVE,
                SyncProbeStatus.FAIL,
                urls.http,
                listOf(SyncProbeNote(R.string.yt_probe_note_no_tracking, "playbackTracking ($playability)")),
            )
    val lengthMs = (tracking.lengthSeconds ?: FALLBACK_LENGTH_S) * MS_PER_SECOND
    val open = call { api.ping(it, tracking.playbackUrl, pingParams(newCpn(), OPEN_POSITION_MS, lengthMs)) }
    if (!open.ok) return fail(HISTORY_REMOVE, open)

    val token = readUntil({ it != null }, HISTORY_READ_ATTEMPTS) { api.historyFeedbackToken(it, videoId) }.value
    if (token == null) {
        val listed = call { listedIn(it, HISTORY, videoId) }.value
        val why = if (listed == true) R.string.yt_probe_note_no_token else R.string.yt_probe_note_not_in_history
        val left = if (listed == true) listOf(SyncProbeNote(R.string.yt_probe_note_left_in, HISTORY)) else emptyList()
        return report(HISTORY_REMOVE, SyncProbeStatus.SKIPPED, null, listOf(SyncProbeNote(why, videoId)) + left)
    }
    pace()
    val feedback = call { api.feedback(it, listOf(token)) }
    if (!feedback.ok) return fail(HISTORY_REMOVE, feedback, SyncProbeNote(R.string.yt_probe_note_left_in, HISTORY))
    val listed = readUntil({ !it }, HISTORY_READ_ATTEMPTS) { listedIn(it, HISTORY, videoId) }
    report(
        HISTORY_REMOVE,
        verdict(listed.value?.not()),
        feedback.http,
        listOf(SyncProbeNote(R.string.yt_probe_note_token_found, videoId), listedNote(HISTORY, listed.value)),
    )
}

private suspend fun SyncProbeSession.historyNotice(auth: YouTubeAccountApi.Auth): Boolean =
    api.browse(auth, browseId = HISTORY).objectsWithAnyKey(NOTICE_KEYS).any()

/**
 * Pauses history, then resumes it, so the run always ends with history on. Google has no read of the
 * setting for this token, so the read-back looks for the notice the history page shows while paused.
 */
internal suspend fun SyncProbeSession.probeHistoryPause() {
    running(HISTORY_PAUSE)
    val before = call { historyNotice(it) }
    val privacy = call { api.browse(it, browseId = PRIVACY_PAGE) }
    val privacyNote = SyncProbeNote(R.string.yt_probe_note_privacy_page, privacy.http?.toString() ?: privacy.error.orEmpty())
    val pause = call { api.setWatchHistoryPaused(it, true) }
    if (!pause.ok) {
        fail(HISTORY_PAUSE, pause, privacyNote)
        skip(HISTORY_RESUME, needs(HISTORY_PAUSE))
        return
    }
    val during = readUntil({ it }) { historyNotice(it) }
    val noticeAppeared = before.value == false && during.value == true
    report(
        HISTORY_PAUSE,
        if (noticeAppeared) SyncProbeStatus.PASS else SyncProbeStatus.UNVERIFIED,
        pause.http,
        listOf(SyncProbeNote(R.string.yt_probe_note_notice, before.value ?: UNKNOWN, during.value ?: UNKNOWN), privacyNote),
    )

    pace()
    running(HISTORY_RESUME)
    val resume = call { api.setWatchHistoryPaused(it, false) }
    if (!resume.ok) return fail(HISTORY_RESUME, resume, SyncProbeNote(R.string.yt_probe_note_left_paused))
    val after = readUntil({ !it }) { historyNotice(it) }
    report(
        HISTORY_RESUME,
        if (noticeAppeared && after.value == false) SyncProbeStatus.PASS else SyncProbeStatus.UNVERIFIED,
        resume.http,
        listOf(SyncProbeNote(R.string.yt_probe_note_notice, during.value ?: UNKNOWN, after.value ?: UNKNOWN)),
    )
}

/** The TV token on a YouTube Music request: expected to be refused, which is why music library writes are out. */
internal suspend fun SyncProbeSession.probeWebRemix() {
    running(WEB_REMIX)
    val browse = call { api.browse(it, browseId = MUSIC_LIKES_PAGE, client = AccountClient.WEB_REMIX) }
    val refused = browse.http == HTTP_BAD_REQUEST
    report(
        WEB_REMIX,
        if (refused) SyncProbeStatus.PASS else SyncProbeStatus.FAIL,
        browse.http,
        listOf(
            SyncProbeNote(
                if (browse.ok) R.string.yt_probe_note_remix_accepted else R.string.yt_probe_note_remix_refused,
                browse.error ?: "",
            ),
        ),
    )
}
