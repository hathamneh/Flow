package io.github.aedev.flow.data.youtubeaccount.probe

import io.github.aedev.flow.R
import io.github.aedev.flow.data.youtubeaccount.AccountClient
import io.github.aedev.flow.data.youtubeaccount.PlaylistEdit
import io.github.aedev.flow.data.youtubeaccount.PlaylistEntry
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.PLAYLIST_ADD
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.PLAYLIST_CREATE_TV
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.PLAYLIST_CREATE_WEB
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.PLAYLIST_DELETE_TV
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.PLAYLIST_DELETE_WEB
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.PLAYLIST_MOVE
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.PLAYLIST_REMOVE
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.PLAYLIST_RENAME
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.PLAYLIST_SET_VIDEO_ID
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeSession.Companion.PLAYLISTS
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeSession.Companion.SECOND_VIDEO
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeSession.Companion.UNKNOWN
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeSession.Companion.VIDEO
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeSession.Companion.WATCH_LATER
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeSession.Companion.WATCH_LATER_BROWSE
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Throwaway playlists are named this, so anything a crashed run leaves behind is easy to spot on youtube.com. */
internal const val PROBE_PLAYLIST_TITLE = "Flow probe"

private val EDIT_CHECKS = listOf(PLAYLIST_ADD, PLAYLIST_SET_VIDEO_ID, PLAYLIST_MOVE, PLAYLIST_RENAME, PLAYLIST_REMOVE)

internal suspend fun SyncProbeSession.probePlaylists() {
    val title = "$PROBE_PLAYLIST_TITLE ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}"
    val tvId = create(PLAYLIST_CREATE_TV, "$title TV", AccountClient.TV)
    pace()
    val webId = create(PLAYLIST_CREATE_WEB, "$title WEB", AccountClient.WEB)
    val target = tvId ?: webId
    if (target == null) {
        listOf(PLAYLIST_DELETE_TV, PLAYLIST_DELETE_WEB).forEach { skip(it, SyncProbeNote(R.string.yt_probe_note_no_playlist)) }
        pace()
        probeWatchLaterEdits()
        return
    }
    pace()
    probePlaylistEdits(target, title)
    pace()
    probePlaylistDeletes(tvId, webId)
}

private suspend fun SyncProbeSession.create(
    check: SyncProbeCheck,
    title: String,
    client: AccountClient,
): String? {
    running(check)
    val privateTry = call { api.createPlaylist(it, title, listOf(VIDEO), client) }
    val created = if (privateTry.ok) privateTry else call { api.createPlaylist(it, title, listOf(VIDEO), client, private = false) }
    val publicNote =
        if (privateTry.ok) {
            null
        } else {
            SyncProbeNote(
                R.string.yt_probe_note_create_public,
                created.http?.toString() ?: created.error.orEmpty(),
            )
        }
    if (!created.ok) {
        fail(check, privateTry, *listOfNotNull(publicNote).toTypedArray())
        return null
    }
    val id = created.value
    if (id == null) {
        report(check, SyncProbeStatus.NOT_REFLECTED, created.http, listOf(SyncProbeNote(R.string.yt_probe_note_no_playlist_id)))
        return null
    }
    val listed = readUntil({ id in it }) { libraryPlaylists(it) }
    val entries = call { entries(it, id) }
    report(
        check,
        verdict(listed.value?.containsKey(id), entries.value?.any { it.videoId == VIDEO }),
        created.http,
        listOfNotNull(
            SyncProbeNote(R.string.yt_probe_note_playlist_id, id),
            listedNote(PLAYLISTS, listed.value?.containsKey(id)),
            SyncProbeNote(R.string.yt_probe_note_order, entries.value?.order() ?: UNKNOWN),
            publicNote,
        ),
    )
    return id
}

private suspend fun SyncProbeSession.probePlaylistEdits(
    id: String,
    title: String,
) {
    running(PLAYLIST_ADD)
    val first = call { api.editPlaylist(it, id, listOf(PlaylistEdit.Add(SECOND_VIDEO))) }
    if (!first.ok) {
        fail(PLAYLIST_ADD, first)
    } else {
        pace()
        val again = call { api.editPlaylist(it, id, listOf(PlaylistEdit.Add(SECOND_VIDEO))) }
        val copies =
            readUntil({ entries -> entries.any { it.videoId == SECOND_VIDEO } }) { entries(it, id) }
                .value
                ?.count { it.videoId == SECOND_VIDEO }
        report(
            PLAYLIST_ADD,
            verdict(copies?.let { it == 1 }),
            first.http,
            listOf(
                SyncProbeNote(R.string.yt_probe_note_copies, copies ?: UNKNOWN),
                SyncProbeNote(R.string.yt_probe_note_second_add, again.http?.toString() ?: again.error.orEmpty()),
            ),
        )
    }

    pace()
    running(PLAYLIST_SET_VIDEO_ID)
    val read = call { entries(it, id) }
    val entries = read.value.orEmpty()
    val withIds = entries.count { it.setVideoId != null }
    if (!read.ok) {
        fail(PLAYLIST_SET_VIDEO_ID, read)
    } else {
        report(
            PLAYLIST_SET_VIDEO_ID,
            if (entries.isNotEmpty() && withIds == entries.size) SyncProbeStatus.PASS else SyncProbeStatus.NOT_REFLECTED,
            read.http,
            listOf(SyncProbeNote(R.string.yt_probe_note_set_video_ids, withIds, entries.size)),
        )
    }

    running(PLAYLIST_MOVE)
    // Whichever of the two test videos comes first is moved behind the other, so the check holds
    // whether YouTube appended the second one or put it on top.
    val ours = entries.filter { it.videoId == VIDEO || it.videoId == SECOND_VIDEO }.distinctBy { it.videoId }
    val moved = ours.getOrNull(0)
    val anchor = ours.getOrNull(1)
    val movedId = moved?.setVideoId
    val anchorId = anchor?.setVideoId
    if (moved == null || anchor == null || movedId == null || anchorId == null) {
        skip(PLAYLIST_MOVE, needs(PLAYLIST_SET_VIDEO_ID))
    } else {
        val move = call { api.editPlaylist(it, id, listOf(PlaylistEdit.MoveAfter(movedId, anchorId))) }
        if (!move.ok) {
            fail(PLAYLIST_MOVE, move)
        } else {
            val after = readUntil({ it.isBehind(moved.videoId, anchor.videoId) }) { entries(it, id) }
            report(
                PLAYLIST_MOVE,
                verdict(after.value?.isBehind(moved.videoId, anchor.videoId)),
                move.http,
                listOf(SyncProbeNote(R.string.yt_probe_note_reorder, entries.order(), after.value?.order() ?: UNKNOWN)),
            )
        }
    }

    pace()
    running(PLAYLIST_RENAME)
    if (id == WATCH_LATER) {
        skip(PLAYLIST_RENAME, SyncProbeNote(R.string.yt_probe_note_on_watch_later))
        return removeSecond(id)
    }
    val newName = "$title renamed"
    val rename = call { api.editPlaylist(it, id, listOf(PlaylistEdit.Rename(newName))) }
    if (!rename.ok) {
        fail(PLAYLIST_RENAME, rename)
    } else {
        val shown = readUntil({ it[id] == newName }) { libraryPlaylists(it) }
        report(
            PLAYLIST_RENAME,
            verdict(shown.value?.get(id)?.let { it == newName }),
            rename.http,
            listOf(SyncProbeNote(R.string.yt_probe_note_title, shown.value?.get(id) ?: UNKNOWN)),
        )
    }

    removeSecond(id)
}

private suspend fun SyncProbeSession.removeSecond(id: String) {
    pace()
    running(PLAYLIST_REMOVE)
    val remove = call { api.editPlaylist(it, id, listOf(PlaylistEdit.Remove(SECOND_VIDEO))) }
    if (!remove.ok) return fail(PLAYLIST_REMOVE, remove)
    val after = readUntil({ entries -> entries.none { it.videoId == SECOND_VIDEO } }) { entries(it, id) }
    report(
        PLAYLIST_REMOVE,
        verdict(after.value?.none { it.videoId == SECOND_VIDEO }),
        remove.http,
        listOf(SyncProbeNote(R.string.yt_probe_note_order, after.value?.order() ?: UNKNOWN)),
    )
}

private fun List<PlaylistEntry>.order() = joinToString(" → ") { it.videoId }

private fun List<PlaylistEntry>.isBehind(
    moved: String,
    anchor: String,
): Boolean {
    val at = indexOfFirst { it.videoId == anchor }
    return at >= 0 && indexOfFirst { it.videoId == moved } > at
}

/**
 * The edit checks on Watch later, for when YouTube will not create a throwaway playlist. Runs only
 * when neither test video is already there, and takes both out again at the end.
 */
private suspend fun SyncProbeSession.probeWatchLaterEdits() {
    val skipNote = SyncProbeNote(R.string.yt_probe_note_on_watch_later)
    val before = call { entries(it, WATCH_LATER) }
    val existing = before.value ?: return EDIT_CHECKS.forEach { fail(it, before, skipNote) }
    if (existing.any { it.videoId == VIDEO || it.videoId == SECOND_VIDEO }) {
        return EDIT_CHECKS.forEach { skip(it, SyncProbeNote(R.string.yt_probe_note_already, WATCH_LATER_BROWSE)) }
    }
    val seed = call { api.editPlaylist(it, WATCH_LATER, listOf(PlaylistEdit.Add(VIDEO))) }
    if (!seed.ok) return EDIT_CHECKS.forEach { fail(it, seed, skipNote) }
    readUntil({ entries -> entries.any { it.videoId == VIDEO } }) { entries(it, WATCH_LATER) }
    pace()
    probePlaylistEdits(WATCH_LATER, PROBE_PLAYLIST_TITLE)
    pace()
    val leftover =
        call {
            entries(
                it,
                WATCH_LATER,
            )
        }.value.orEmpty().map { it.videoId }.filter { it == VIDEO || it == SECOND_VIDEO }.distinct()
    val cleanup = call { api.editPlaylist(it, WATCH_LATER, leftover.map(PlaylistEdit::Remove)) }
    if (leftover.isNotEmpty() && !cleanup.ok) {
        report(
            PLAYLIST_REMOVE,
            SyncProbeStatus.FAIL,
            cleanup.http,
            listOf(errorNote(cleanup), SyncProbeNote(R.string.yt_probe_note_left_in, WATCH_LATER_BROWSE)),
        )
    }
}

/**
 * Deletes the throwaway playlists: each check with its own body first, then whatever is left with
 * either body, so a refused delete does not leave a playlist behind silently.
 */
private suspend fun SyncProbeSession.probePlaylistDeletes(
    tvId: String?,
    webId: String?,
) {
    val remaining = linkedSetOf<String>().apply { listOfNotNull(tvId, webId).forEach(::add) }
    val tvTarget = tvId ?: webId ?: return
    val (tvResult, tvGone) = delete(PLAYLIST_DELETE_TV, tvTarget, AccountClient.TV)
    report(tvResult.check, tvResult.status, tvResult.httpStatus, tvResult.notes)
    if (tvGone) remaining -= tvTarget

    pace()
    val webTarget = webId?.takeIf { it in remaining } ?: remaining.firstOrNull()
    if (webTarget == null) return skip(PLAYLIST_DELETE_WEB, SyncProbeNote(R.string.yt_probe_note_nothing_left))
    val (webResult, webGone) = delete(PLAYLIST_DELETE_WEB, webTarget, AccountClient.WEB)
    if (webGone) remaining -= webTarget

    remaining.toList().forEach { id ->
        val gone = listOf(AccountClient.TV, AccountClient.WEB).any { client -> call { api.deletePlaylist(it, id, client) }.ok }
        if (gone) remaining -= id
    }
    val leftovers = remaining.map { SyncProbeNote(R.string.yt_probe_note_left_behind, it) }
    report(webResult.check, webResult.status, webResult.httpStatus, webResult.notes + leftovers)
}

private suspend fun SyncProbeSession.delete(
    check: SyncProbeCheck,
    id: String,
    client: AccountClient,
): Pair<SyncProbeResult, Boolean> {
    running(check)
    val deleted = call { api.deletePlaylist(it, id, client) }
    if (!deleted.ok) {
        return SyncProbeResult(
            check,
            SyncProbeStatus.FAIL,
            deleted.http,
            listOf(errorNote(deleted), SyncProbeNote(R.string.yt_probe_note_playlist_id, id)),
        ) to
            false
    }
    val listed = readUntil({ id !in it }) { libraryPlaylists(it) }
    val stillListed = listed.value?.containsKey(id)
    val result =
        SyncProbeResult(
            check,
            verdict(stillListed?.not()),
            deleted.http,
            listOf(SyncProbeNote(R.string.yt_probe_note_playlist_id, id), listedNote(PLAYLISTS, stillListed)),
        )
    return result to (stillListed != true)
}
