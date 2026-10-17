package io.github.aedev.flow.data.youtubeaccount.probe

import io.github.aedev.flow.R
import io.github.aedev.flow.data.youtubeaccount.LikeAction
import io.github.aedev.flow.data.youtubeaccount.PlaylistEdit
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccountApi
import io.github.aedev.flow.data.youtubeaccount.YouTubeAccountParsers
import io.github.aedev.flow.data.youtubeaccount.browsePages
import io.github.aedev.flow.data.youtubeaccount.objectsWithKey
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.BELL
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.DISLIKE
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.LIKE
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.MUSIC_LIKE
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.REMOVE_LIKE
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.SUBSCRIBE
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.UNSUBSCRIBE
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.WATCH_LATER_ADD
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeCheck.WATCH_LATER_REMOVE
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeSession.Companion.CHANNELS
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeSession.Companion.LIKED_MUSIC
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeSession.Companion.LIKED_VIDEOS
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeSession.Companion.LIST_PAGES
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeSession.Companion.MUSIC_VIDEO
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeSession.Companion.VIDEO
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeSession.Companion.WATCH_LATER
import io.github.aedev.flow.data.youtubeaccount.probe.SyncProbeSession.Companion.WATCH_LATER_BROWSE
import io.github.aedev.flow.data.youtubeaccount.string

private const val LIKED = "LIKE"
private const val DISLIKED = "DISLIKE"
private const val NEUTRAL = "INDIFFERENT"

private suspend fun SyncProbeSession.likeStatus(
    auth: YouTubeAccountApi.Auth,
    videoId: String,
) = YouTubeAccountParsers.likeStatus(api.next(auth, videoId), videoId)

internal suspend fun SyncProbeSession.probeLikes() {
    running(LIKE)
    val before = call { likeStatus(it, VIDEO) }
    if (!before.ok) {
        listOf(LIKE, REMOVE_LIKE, DISLIKE).forEach { fail(it, before) }
        return
    }
    val prior = before.value
    if (prior != null && prior != NEUTRAL) {
        listOf(LIKE, REMOVE_LIKE, DISLIKE).forEach { skip(it, SyncProbeNote(R.string.yt_probe_note_already, prior)) }
        return
    }

    val like = call { api.rate(it, VIDEO, LikeAction.LIKE) }
    if (!like.ok) {
        fail(LIKE, like)
        skip(REMOVE_LIKE, needs(LIKE))
    } else {
        val status = readUntil({ it == LIKED }) { likeStatus(it, VIDEO) }
        val listed = readUntil({ it }) { listedIn(it, LIKED_VIDEOS, VIDEO) }
        report(
            LIKE,
            verdict(status.value?.let { it == LIKED }, listed.value),
            like.http,
            listOf(stateNote(prior, status.value), listedNote(LIKED_VIDEOS, listed.value)),
        )
        pace()
        running(REMOVE_LIKE)
        val remove = call { api.rate(it, VIDEO, LikeAction.REMOVE) }
        if (!remove.ok) {
            fail(REMOVE_LIKE, remove, SyncProbeNote(R.string.yt_probe_note_left_liked, VIDEO))
        } else {
            val after = readUntil({ it == NEUTRAL }) { likeStatus(it, VIDEO) }
            val stillListed = readUntil({ !it }) { listedIn(it, LIKED_VIDEOS, VIDEO) }
            report(
                REMOVE_LIKE,
                verdict(after.value?.let { it == NEUTRAL }, stillListed.value?.not()),
                remove.http,
                listOf(stateNote(status.value, after.value), listedNote(LIKED_VIDEOS, stillListed.value)),
            )
        }
    }

    pace()
    running(DISLIKE)
    val dislike = call { api.rate(it, VIDEO, LikeAction.DISLIKE) }
    if (!dislike.ok) return fail(DISLIKE, dislike)
    val disliked = readUntil({ it == DISLIKED }) { likeStatus(it, VIDEO) }
    pace()
    val undo = call { api.rate(it, VIDEO, LikeAction.REMOVE) }
    val after = if (undo.ok) readUntil({ it == NEUTRAL }) { likeStatus(it, VIDEO) } else null
    report(
        DISLIKE,
        verdict(disliked.value?.let { it == DISLIKED }, if (undo.ok) after?.value?.let { it == NEUTRAL } else false),
        dislike.http,
        listOfNotNull(
            stateNote(prior, disliked.value),
            if (undo.ok) {
                SyncProbeNote(R.string.yt_probe_note_remove_dislike, undo.http ?: 0, after?.value ?: SyncProbeSession.UNKNOWN)
            } else {
                SyncProbeNote(R.string.yt_probe_note_cleanup_failed, undo.error.orEmpty())
            },
        ),
    )
}

internal suspend fun SyncProbeSession.probeMusicLike() {
    running(MUSIC_LIKE)
    val before = call { likeStatus(it, MUSIC_VIDEO) }
    if (!before.ok) return fail(MUSIC_LIKE, before)
    val prior = before.value
    if (prior != null && prior != NEUTRAL) return skip(MUSIC_LIKE, SyncProbeNote(R.string.yt_probe_note_already, prior))
    val like = call { api.rate(it, MUSIC_VIDEO, LikeAction.LIKE) }
    if (!like.ok) return fail(MUSIC_LIKE, like)
    val listed = readUntil({ it }) { listedIn(it, LIKED_MUSIC, MUSIC_VIDEO) }
    pace()
    val undo = call { api.rate(it, MUSIC_VIDEO, LikeAction.REMOVE) }
    report(
        MUSIC_LIKE,
        verdict(listed.value),
        like.http,
        listOf(
            listedNote(LIKED_MUSIC, listed.value),
            if (undo.ok) {
                SyncProbeNote(
                    R.string.yt_probe_note_cleaned_up,
                )
            } else {
                SyncProbeNote(R.string.yt_probe_note_cleanup_failed, undo.error.orEmpty())
            },
        ),
    )
}

internal suspend fun SyncProbeSession.probeSubscription() {
    running(SUBSCRIBE)
    val page = call { api.next(it, VIDEO) }
    val root = page.value
    if (!page.ok || root == null) {
        listOf(SUBSCRIBE, BELL, UNSUBSCRIBE).forEach { fail(it, page) }
        return
    }
    // The owner's subscribe button names the channel, so the check never depends on a hardcoded id.
    val channelId = root.objectsWithKey("subscribed").firstNotNullOfOrNull { it.string("channelId") }
    if (channelId == null) {
        listOf(SUBSCRIBE, BELL, UNSUBSCRIBE).forEach { skip(it, SyncProbeNote(R.string.yt_probe_note_no_subscribe_button)) }
        return
    }
    if (YouTubeAccountParsers.isSubscribed(root, channelId) == true) {
        listOf(SUBSCRIBE, BELL, UNSUBSCRIBE).forEach { skip(it, SyncProbeNote(R.string.yt_probe_note_already, channelId)) }
        return
    }

    val subscribe = call { api.setSubscribed(it, channelId, true) }
    if (!subscribe.ok) {
        fail(SUBSCRIBE, subscribe)
        skip(BELL, needs(SUBSCRIBE))
        skip(UNSUBSCRIBE, needs(SUBSCRIBE))
        return
    }
    val shown = readUntil({ it == true }) { YouTubeAccountParsers.isSubscribed(api.next(it, VIDEO), channelId) }
    val listed = readUntil({ it }) { channelListed(it, channelId) }
    report(
        SUBSCRIBE,
        verdict(shown.value, listed.value),
        subscribe.http,
        listOf(
            SyncProbeNote(R.string.yt_probe_note_subscribed_flag, channelId, shown.value ?: SyncProbeSession.UNKNOWN),
            listedNote(CHANNELS, listed.value),
        ),
    )

    pace()
    probeBell(channelId)

    pace()
    running(UNSUBSCRIBE)
    val unsubscribe = call { api.setSubscribed(it, channelId, false) }
    if (!unsubscribe.ok) return fail(UNSUBSCRIBE, unsubscribe, SyncProbeNote(R.string.yt_probe_note_left_subscribed, channelId))
    val gone = readUntil({ it == false }) { YouTubeAccountParsers.isSubscribed(api.next(it, VIDEO), channelId) }
    val stillListed = readUntil({ !it }) { channelListed(it, channelId) }
    report(
        UNSUBSCRIBE,
        verdict(gone.value?.not(), stillListed.value?.not()),
        unsubscribe.http,
        listOf(
            SyncProbeNote(R.string.yt_probe_note_subscribed_flag, channelId, gone.value ?: SyncProbeSession.UNKNOWN),
            listedNote(CHANNELS, stillListed.value),
        ),
    )
}

private suspend fun SyncProbeSession.probeBell(channelId: String) {
    running(BELL)
    val page = readUntil({ YouTubeAccountParsers.bellOptions(it, channelId).isNotEmpty() }) { api.next(it, VIDEO) }
    val options = page.value?.let { YouTubeAccountParsers.bellOptions(it, channelId) }.orEmpty()
    if (!page.ok) return fail(BELL, page)
    if (options.isEmpty()) return skip(BELL, SyncProbeNote(R.string.yt_probe_note_no_bell))
    val current = options.firstOrNull { it.selected }
    val target =
        options.firstOrNull { !it.selected && it.state.isAllNotifications() } ?: options.firstOrNull { !it.selected } ?: options.first()
    val optionsNote = SyncProbeNote(R.string.yt_probe_note_bell_options, options.joinToString { it.state })
    // The TV app changes the bell by re-subscribing with the choice's params; try that when the bell endpoint refuses.
    val direct = call { api.setNotificationPreference(it, target.params) }
    val set = if (direct.ok) direct else call { api.setSubscribed(it, channelId, true, target.params) }
    val route =
        SyncProbeNote(
            R.string.yt_probe_note_bell_route,
            direct.http?.toString() ?: direct.error.orEmpty(),
            if (direct.ok) "-" else set.http?.toString() ?: set.error.orEmpty(),
        )
    if (!set.ok) return fail(BELL, set, route, optionsNote)
    val after =
        readUntil({ it == target.state }) { auth ->
            YouTubeAccountParsers.bellOptions(api.next(auth, VIDEO), channelId).firstOrNull { it.selected }?.state
        }
    report(
        BELL,
        verdict(after.value?.let { it == target.state }),
        set.http,
        listOf(
            SyncProbeNote(
                R.string.yt_probe_note_bell,
                current?.state ?: SyncProbeSession.UNKNOWN,
                target.state,
                after.value ?: SyncProbeSession.UNKNOWN,
            ),
            route,
            optionsNote,
        ),
    )
}

private fun String.isAllNotifications() = endsWith("_ALL") || this == "NOTIFICATIONS_ACTIVE"

private suspend fun SyncProbeSession.channelListed(
    auth: YouTubeAccountApi.Auth,
    channelId: String,
): Boolean {
    var found = false
    api.browsePages(auth, CHANNELS, LIST_PAGES) { page ->
        found = YouTubeAccountParsers.channels(page).any { it.channelId == channelId }
        !found
    }
    return found
}

internal suspend fun SyncProbeSession.probeWatchLater() {
    running(WATCH_LATER_ADD)
    val before = call { listedIn(it, WATCH_LATER_BROWSE, VIDEO) }
    if (!before.ok) {
        fail(WATCH_LATER_ADD, before)
        fail(WATCH_LATER_REMOVE, before)
        return
    }
    if (before.value == true) {
        listOf(WATCH_LATER_ADD, WATCH_LATER_REMOVE).forEach { skip(it, SyncProbeNote(R.string.yt_probe_note_already, WATCH_LATER_BROWSE)) }
        return
    }
    val add = call { api.editPlaylist(it, WATCH_LATER, listOf(PlaylistEdit.Add(VIDEO))) }
    if (!add.ok) {
        fail(WATCH_LATER_ADD, add)
        skip(WATCH_LATER_REMOVE, needs(WATCH_LATER_ADD))
        return
    }
    val listed = readUntil({ it }) { listedIn(it, WATCH_LATER_BROWSE, VIDEO) }
    report(WATCH_LATER_ADD, verdict(listed.value), add.http, listOf(listedNote(WATCH_LATER_BROWSE, listed.value)))

    pace()
    running(WATCH_LATER_REMOVE)
    val remove = call { api.editPlaylist(it, WATCH_LATER, listOf(PlaylistEdit.Remove(VIDEO))) }
    if (!remove.ok) return fail(WATCH_LATER_REMOVE, remove, SyncProbeNote(R.string.yt_probe_note_left_in, WATCH_LATER_BROWSE))
    val stillListed = readUntil({ !it }) { listedIn(it, WATCH_LATER_BROWSE, VIDEO) }
    report(WATCH_LATER_REMOVE, verdict(stillListed.value?.not()), remove.http, listOf(listedNote(WATCH_LATER_BROWSE, stillListed.value)))
}
