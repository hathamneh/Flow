package io.github.aedev.flow.data.youtubeaccount.probe

import io.github.aedev.flow.R
import io.github.aedev.flow.data.youtubeaccount.PlayerVariant
import io.github.aedev.flow.data.youtubeaccount.PlaylistEntry
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccountApi
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccountManager
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccountParsers
import io.github.aedev.flow.data.youtubeaccount.YouTubeHttpException
import io.github.aedev.flow.data.youtubeaccount.browsePages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** What one request of a check came back with. */
internal class ProbeCall<T>(
    val http: Int?,
    val value: T?,
    val error: String?,
) {
    val ok: Boolean
        get() = error == null
}

/**
 * One run of the sync diagnostics: the request and read-back helpers the checks share, and the
 * order they run in. Every request goes through the active account's auth inside the app.
 */
internal class SyncProbeSession(
    val api: YouTubeAccountApi,
    private val accounts: YouTubeAccountManager,
    private val publish: (SyncProbeResult) -> Unit,
    val visitorData: String? = null,
) {
    /** The `/player` request the history checks use: the first that returned tracking URLs, else plain TV. */
    var playerVariant: PlayerVariant = PlayerVariant.TV

    suspend fun runAll() {
        probeLikes()
        probeMusicLike()
        probeSubscription()
        probeWatchLater()
        probePlaylists()
        probeOwnPlaylistRename(probePlaylistOwnership())
        probePlaylistSave()
        probePlayerVariants()
        probeWatchedPing()
        probeHistoryRemove()
        probeHistoryPause()
        probeWebRemix()
    }

    suspend fun <T> call(block: suspend (YouTubeAccountApi.Auth) -> T): ProbeCall<T> =
        try {
            val box =
                accounts.withActiveAuth { _, auth -> Box(block(auth)) }
                    ?: return ProbeCall(null, null, SIGNED_OUT)
            ProbeCall(HTTP_OK, box.value, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: YouTubeHttpException) {
            ProbeCall(e.code, null, "HTTP ${e.code}")
        } catch (e: Exception) {
            ProbeCall(null, null, "${e.javaClass.simpleName}: ${e.message}")
        }

    /** Reads until [done] holds, a few times with a pause: YouTube's own pages trail a write by seconds. */
    suspend fun <T> readUntil(
        done: (T) -> Boolean,
        attempts: Int = READ_ATTEMPTS,
        read: suspend (YouTubeAccountApi.Auth) -> T,
    ): ProbeCall<T> {
        var last = call(read)
        repeat(attempts - 1) {
            val value = last.value
            if (last.ok && value != null && done(value)) return last
            delay(READ_PAUSE_MS)
            last = call(read)
        }
        return last
    }

    suspend fun pace() = delay(PACE_MS)

    fun running(check: SyncProbeCheck) = publish(SyncProbeResult(check, SyncProbeStatus.RUNNING))

    fun report(
        check: SyncProbeCheck,
        status: SyncProbeStatus,
        http: Int?,
        notes: List<SyncProbeNote>,
    ) = publish(SyncProbeResult(check, status, http, notes))

    fun skip(
        check: SyncProbeCheck,
        note: SyncProbeNote,
    ) = report(check, SyncProbeStatus.SKIPPED, null, listOf(note))

    fun fail(
        check: SyncProbeCheck,
        call: ProbeCall<*>,
        vararg extra: SyncProbeNote,
    ) = report(check, SyncProbeStatus.FAIL, call.http, listOf(errorNote(call)) + extra)

    fun errorNote(call: ProbeCall<*>) = SyncProbeNote(R.string.yt_probe_note_error, call.error.orEmpty())

    fun needs(check: SyncProbeCheck) = SyncProbeNote(R.string.yt_probe_note_needs, check.name)

    fun listedNote(
        browseId: String,
        listed: Boolean?,
    ) = when (listed) {
        true -> SyncProbeNote(R.string.yt_probe_note_listed, browseId)
        false -> SyncProbeNote(R.string.yt_probe_note_not_listed, browseId)
        null -> SyncProbeNote(R.string.yt_probe_note_unread, browseId)
    }

    fun stateNote(
        before: String?,
        after: String?,
    ) = SyncProbeNote(R.string.yt_probe_note_state, before ?: UNKNOWN, after ?: UNKNOWN)

    /** Each read-back is true (shows the change), false (does not) or null (could not tell). */
    fun verdict(vararg readbacks: Boolean?): SyncProbeStatus {
        val known = readbacks.filterNotNull()
        return when {
            known.isEmpty() -> SyncProbeStatus.UNVERIFIED
            known.all { it } -> SyncProbeStatus.PASS
            else -> SyncProbeStatus.NOT_REFLECTED
        }
    }

    suspend fun listedIn(
        auth: YouTubeAccountApi.Auth,
        browseId: String,
        videoId: String,
    ): Boolean {
        var found = false
        api.browsePages(auth, browseId, LIST_PAGES) { page ->
            found = YouTubeAccountParsers.videos(page).any { it.videoId == videoId }
            !found
        }
        return found
    }

    /** The account's playlists, id to title, from the library page. */
    suspend fun libraryPlaylists(auth: YouTubeAccountApi.Auth): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        api.browsePages(auth, PLAYLISTS, LIST_PAGES) { page ->
            val before = found.size
            YouTubeAccountParsers.playlists(page).forEach { found.putIfAbsent(it.playlistId, it.title) }
            found.size > before
        }
        return found
    }

    suspend fun entries(
        auth: YouTubeAccountApi.Auth,
        playlistId: String,
    ): List<PlaylistEntry> {
        val found = mutableListOf<PlaylistEntry>()
        api.browsePages(auth, "VL$playlistId", LIST_PAGES) { page ->
            val before = found.size
            found += YouTubeAccountParsers.playlistEntries(page)
            found.size > before
        }
        return found
    }

    private class Box<T>(
        val value: T,
    )

    companion object {
        /** "Me at the zoo": short, permanent, and very unlikely to be in anyone's likes or Watch later. */
        const val VIDEO = "jNQXAC9IVRw"
        const val SECOND_VIDEO = "9bZkp7q19f0"
        const val MUSIC_VIDEO = "dQw4w9WgXcQ"

        /** Tried in order for the history check; the first one not already in the account's history is used. */
        val HISTORY_VIDEOS = listOf("M7lc1UVf-VE", "aqz-KE-bpKQ", "eRsGyueVLvQ")
        const val LIKED_VIDEOS = "VLLL"
        const val LIKED_MUSIC = "VLLM"
        const val WATCH_LATER = "WL"
        const val WATCH_LATER_BROWSE = "VLWL"
        const val CHANNELS = "FEchannels"
        const val HISTORY = "FEhistory"
        const val PLAYLISTS = "FEplaylist_aggregation"
        const val UNKNOWN = "?"
        const val SIGNED_OUT = "No signed-in account"
        const val HTTP_OK = 200
        const val LIST_PAGES = 3
        const val READ_ATTEMPTS = 4
        const val READ_PAUSE_MS = 2_500L
        const val PACE_MS = 1_000L
    }
}
