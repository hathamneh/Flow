package io.github.aedev.flow.data.youtubeaccount.probe

import androidx.annotation.StringRes
import io.github.aedev.flow.R

/** Every write the sync diagnostics try, in the order they run. */
enum class SyncProbeCheck(
    @StringRes val title: Int,
) {
    LIKE(R.string.yt_probe_check_like),
    REMOVE_LIKE(R.string.yt_probe_check_remove_like),
    DISLIKE(R.string.yt_probe_check_dislike),
    MUSIC_LIKE(R.string.yt_probe_check_music_like),
    SUBSCRIBE(R.string.yt_probe_check_subscribe),
    BELL(R.string.yt_probe_check_bell),
    UNSUBSCRIBE(R.string.yt_probe_check_unsubscribe),
    WATCH_LATER_ADD(R.string.yt_probe_check_watch_later_add),
    WATCH_LATER_REMOVE(R.string.yt_probe_check_watch_later_remove),
    PLAYLIST_CREATE_TV(R.string.yt_probe_check_playlist_create_tv),
    PLAYLIST_CREATE_WEB(R.string.yt_probe_check_playlist_create_web),
    PLAYLIST_ADD(R.string.yt_probe_check_playlist_add),
    PLAYLIST_SET_VIDEO_ID(R.string.yt_probe_check_playlist_set_video_id),
    PLAYLIST_MOVE(R.string.yt_probe_check_playlist_move),
    PLAYLIST_RENAME(R.string.yt_probe_check_playlist_rename),
    PLAYLIST_REMOVE(R.string.yt_probe_check_playlist_remove),
    PLAYLIST_DELETE_TV(R.string.yt_probe_check_playlist_delete_tv),
    PLAYLIST_DELETE_WEB(R.string.yt_probe_check_playlist_delete_web),
    PLAYLIST_OWNERSHIP(R.string.yt_probe_check_playlist_ownership),
    PLAYLIST_RENAME_OWN(R.string.yt_probe_check_playlist_rename_own),
    PLAYLIST_SAVE(R.string.yt_probe_check_playlist_save),
    PLAYLIST_UNSAVE(R.string.yt_probe_check_playlist_unsave),
    PLAYER_VARIANTS(R.string.yt_probe_check_player_variants),
    WATCHED_PING(R.string.yt_probe_check_watched_ping),
    HISTORY_REMOVE(R.string.yt_probe_check_history_remove),
    HISTORY_PAUSE(R.string.yt_probe_check_history_pause),
    HISTORY_RESUME(R.string.yt_probe_check_history_resume),
    WEB_REMIX(R.string.yt_probe_check_web_remix),
}

enum class SyncProbeStatus {
    PENDING,
    RUNNING,

    /** Accepted, and the read-back shows the change. */
    PASS,

    /** YouTube refused the request, or it could not be sent. */
    FAIL,

    /** Accepted, but the read-back does not show the change. */
    NOT_REFLECTED,

    /** Accepted, with nothing on the page to read the result back from. */
    UNVERIFIED,

    /** Not run, so the account is left as it was; the notes say why. */
    SKIPPED,

    /** Sent; only a person looking at youtube.com can tell if it worked. */
    MANUAL,
}

/** One line of detail under a check: a string resource and its arguments, which are raw ids, states and errors. */
data class SyncProbeNote(
    @StringRes val text: Int,
    val args: List<String>,
) {
    constructor(
        @StringRes text: Int,
        vararg args: Any,
    ) : this(text, args.map { it.toString() })
}

data class SyncProbeResult(
    val check: SyncProbeCheck,
    val status: SyncProbeStatus = SyncProbeStatus.PENDING,
    /** HTTP status of the write itself; null when it was never sent or failed before a reply. */
    val httpStatus: Int? = null,
    val notes: List<SyncProbeNote> = emptyList(),
)

data class SyncProbeRun(
    val running: Boolean = false,
    val accountName: String? = null,
    val results: List<SyncProbeResult> = emptyList(),
    val finishedAt: Long? = null,
    /** Set when the run could not start at all, such as with no signed-in account. */
    val error: SyncProbeNote? = null,
)
