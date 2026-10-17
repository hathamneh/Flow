package io.github.aedev.flow.data.youtubeaccount.probe

import io.github.aedev.flow.R
import io.github.aedev.flow.data.youtubeaccount.PlaylistEdit
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccountApi
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccountParsers
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.PLAYLIST_OWNERSHIP
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.PLAYLIST_RENAME_OWN
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.PLAYLIST_SAVE
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.PLAYLIST_UNSAVE
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeSession.Companion.PLAYLISTS
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeSession.Companion.UNKNOWN

/** One of the account's library playlists, as the first page of its `VL` browse shows it. */
internal data class LibraryPlaylist(
    val id: String,
    val title: String,
    val editable: Boolean,
    val entries: Int,
    val withSetVideoId: Int,
)

/**
 * Reads the first page of each library playlist and notes which offer edits: how the pull will tell the
 * account's own playlists from saved ones. The owner can check the split against what they know.
 */
internal suspend fun SyncProbeSession.probePlaylistOwnership(): List<LibraryPlaylist> {
    running(PLAYLIST_OWNERSHIP)
    val listed = call { libraryPlaylists(it) }
    val playlists = listed.value ?: return emptyList<LibraryPlaylist>().also { fail(PLAYLIST_OWNERSHIP, listed) }
    val read =
        playlists.entries.take(MAX_PLAYLISTS).mapNotNull { (id, title) ->
            pace()
            call { auth -> firstPage(auth, id, title) }.value
        }
    val own = read.filter { it.editable }
    val saved = read.filterNot { it.editable }
    report(
        PLAYLIST_OWNERSHIP,
        if (read.isEmpty()) SyncProbeStatus.UNVERIFIED else SyncProbeStatus.MANUAL,
        listed.http,
        listOf(
            SyncProbeNote(R.string.yt_probe_note_own_playlists, own.joinToString { "${it.title} (${it.entries})" }.ifEmpty { "none" }),
            SyncProbeNote(R.string.yt_probe_note_saved_playlists, saved.joinToString { it.title }.ifEmpty { "none" }),
            SyncProbeNote(
                R.string.yt_probe_note_set_video_ids,
                read.sumOf { it.withSetVideoId }.toString(),
                read.sumOf { it.entries }.toString(),
            ),
        ),
    )
    return read
}

private suspend fun SyncProbeSession.firstPage(
    auth: YouTubeAccountApi.Auth,
    id: String,
    title: String,
): LibraryPlaylist {
    val page = api.browse(auth, browseId = "VL$id")
    val entries = YouTubeAccountParsers.playlistEntries(page)
    return LibraryPlaylist(id, title, YouTubeAccountParsers.isEditable(page), entries.size, entries.count { it.setVideoId != null })
}

/**
 * Renames the smallest of the account's own playlists for a moment and puts the name back, as the owner
 * agreed to. Runs only on a playlist that offers edits; says plainly if the name could not be restored.
 */
internal suspend fun SyncProbeSession.probeOwnPlaylistRename(playlists: List<LibraryPlaylist>) {
    running(PLAYLIST_RENAME_OWN)
    val target =
        playlists.filter { it.editable }.minByOrNull { it.entries }
            ?: return skip(PLAYLIST_RENAME_OWN, SyncProbeNote(R.string.yt_probe_note_no_own_playlist))
    val testName = "${target.title}$RENAME_SUFFIX"
    val rename = call { api.editPlaylist(it, target.id, listOf(PlaylistEdit.Rename(testName))) }
    if (!rename.ok) return fail(PLAYLIST_RENAME_OWN, rename, SyncProbeNote(R.string.yt_probe_note_playlist_id, target.title))
    val shown = readUntil({ it[target.id] == testName }) { libraryPlaylists(it) }
    pace()
    val restore = call { api.editPlaylist(it, target.id, listOf(PlaylistEdit.Rename(target.title))) }
    val restored =
        restore.ok && readUntil({ it[target.id] == target.title }) { libraryPlaylists(it) }.value?.get(target.id) == target.title
    val notes =
        listOfNotNull(
            SyncProbeNote(R.string.yt_probe_note_title, shown.value?.get(target.id) ?: UNKNOWN),
            if (restored) {
                SyncProbeNote(R.string.yt_probe_note_name_restored, target.title)
            } else {
                SyncProbeNote(R.string.yt_probe_note_left_renamed, testName, target.title)
            },
        )
    val status = if (restored) verdict(shown.value?.get(target.id)?.let { it == testName }) else SyncProbeStatus.FAIL
    report(PLAYLIST_RENAME_OWN, status, rename.http, notes)
}

/** Saves someone else's playlist to the library with `like/like`, and removes it with `like/removelike`. */
internal suspend fun SyncProbeSession.probePlaylistSave() {
    running(PLAYLIST_SAVE)
    val before = call { libraryPlaylists(it) }
    val library = before.value ?: return listOf(PLAYLIST_SAVE, PLAYLIST_UNSAVE).forEach { fail(it, before) }
    if (SAVE_PLAYLIST in library) {
        return listOf(PLAYLIST_SAVE, PLAYLIST_UNSAVE).forEach {
            skip(it, SyncProbeNote(R.string.yt_probe_note_already, SAVE_PLAYLIST))
        }
    }
    val save = call { api.setPlaylistSaved(it, SAVE_PLAYLIST, saved = true) }
    if (!save.ok) {
        fail(PLAYLIST_SAVE, save)
        return skip(PLAYLIST_UNSAVE, needs(PLAYLIST_SAVE))
    }
    val listed = readUntil({ SAVE_PLAYLIST in it }) { libraryPlaylists(it) }
    report(
        PLAYLIST_SAVE,
        verdict(listed.value?.let { SAVE_PLAYLIST in it }),
        save.http,
        listOf(
            listedNote(
                PLAYLISTS,
                listed.value?.let {
                    SAVE_PLAYLIST in
                        it
                },
            ),
        ),
    )
    pace()
    running(PLAYLIST_UNSAVE)
    val unsave = call { api.setPlaylistSaved(it, SAVE_PLAYLIST, saved = false) }
    if (!unsave.ok) return fail(PLAYLIST_UNSAVE, unsave, SyncProbeNote(R.string.yt_probe_note_left_in, PLAYLISTS))
    val gone = readUntil({ SAVE_PLAYLIST !in it }) { libraryPlaylists(it) }
    val removed = gone.value?.let { SAVE_PLAYLIST !in it }
    report(
        PLAYLIST_UNSAVE,
        verdict(removed),
        unsave.http,
        listOfNotNull(
            listedNote(PLAYLISTS, gone.value?.let { SAVE_PLAYLIST in it }),
            SyncProbeNote(R.string.yt_probe_note_left_in, PLAYLISTS).takeIf { removed == false },
        ),
    )
}

private const val MAX_PLAYLISTS = 12
private const val RENAME_SUFFIX = " · Flow test"

/** A public playlist by someone else (Lex Fridman Podcast), very unlikely to be in the owner's library. */
private const val SAVE_PLAYLIST = "PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"
