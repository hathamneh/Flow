package io.github.aedev.flow.data.youtubeaccount

import android.util.Log
import io.github.aedev.flow.data.local.LikedVideoInfo
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.recommendation.music.MusicBrainEngine
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The account's library: liked videos, liked music (YouTube Music likes), Watch later and playlists.
 * Likes and Watch later follow YouTube both ways ([YouTubeLibraryMerge]); each YouTube playlist is kept
 * as a saved playlist in Flow under its own id and refreshed to match, private ones included, since
 * they are read signed in.
 */
@Singleton
class YouTubeLibrarySync
    @Inject
    constructor(
        private val api: YouTubeAccountApi,
        private val playlists: PlaylistRepository,
        private val musicBrain: MusicBrainEngine,
        private val merge: YouTubeLibraryMerge,
    ) {
        data class Result(
            val likes: Int,
            val playlists: Int,
        )

        suspend fun pull(
            auth: YouTubeAccountApi.Auth,
            accountId: String,
        ): Result {
            val likes = pullLikes(auth, accountId, LIKE_PAGES)
            merge.watchLater(auth, accountId, WATCH_LATER_PAGES)
            val playlistCount = pullPlaylists(auth)
            return Result(likes = likes, playlists = playlistCount)
        }

        /**
         * The first page of likes, liked music and Watch later: what changed on YouTube lately, for the
         * frequent light sync. Three requests; the full walk stays on the library's slower cadence.
         */
        suspend fun pullRecent(
            auth: YouTubeAccountApi.Auth,
            accountId: String,
        ): Int {
            val likes = pullLikes(auth, accountId, RECENT_PAGES)
            merge.watchLater(auth, accountId, RECENT_PAGES)
            return likes
        }

        private suspend fun pullLikes(
            auth: YouTubeAccountApi.Auth,
            accountId: String,
            pages: Int,
        ): Int {
            val likedVideos = merge.likes(auth, accountId, music = false, maxPages = pages)
            val likedMusic = merge.likes(auth, accountId, music = true, maxPages = pages)
            learnMusic(likedMusic)
            return likedVideos.size + likedMusic.size
        }

        /** New YouTube Music likes count as likes for Flow's music engine, as a like made in Flow does. */
        private suspend fun learnMusic(added: List<LikedVideoInfo>) {
            added.take(MUSIC_LEARN_LIMIT).forEach { like ->
                runCatching {
                    musicBrain.onExplicitLike(
                        MusicTrack(
                            videoId = like.videoId,
                            title = like.title,
                            artist = like.channelName,
                            thumbnailUrl = like.thumbnail,
                            duration = like.durationSeconds,
                            channelId = like.channelId.orEmpty(),
                        ),
                    )
                }.onFailure { Log.w(TAG, "Music engine could not learn from a liked song", it) }
            }
        }

        private suspend fun pullPlaylists(auth: YouTubeAccountApi.Auth): Int {
            val found = LinkedHashMap<String, RemotePlaylist>()
            api.browsePages(auth, PLAYLISTS, PLAYLIST_LIST_PAGES) { page ->
                val before = found.size
                YouTubeAccountParsers.playlists(page).forEach { found.putIfAbsent(it.playlistId, it) }
                found.size > before
            }
            val wanted =
                found.values.take(MAX_PLAYLISTS).filterNot { playlists.getPlaylistEntity(it.playlistId)?.isUserCreated == true }
            // One bounded round at a time: a few playlists browse together instead of all sixty in a row.
            var saved = 0
            wanted.chunked(PARALLEL_PLAYLISTS).forEach { round ->
                val fetched =
                    coroutineScope {
                        round
                            .map { playlist ->
                                async(PerformanceDispatcher.networkIO) {
                                    playlist to api.collectVideos(auth, PLAYLIST_BROWSE_PREFIX + playlist.playlistId, PLAYLIST_PAGES).items
                                }
                            }.awaitAll()
                    }
                fetched.forEach { (playlist, items) ->
                    if (items.isEmpty()) return@forEach
                    val isMusic = items.count { it.isMusic } * 2 > items.size
                    val thumbnail = playlist.thumbnailUrl.ifBlank { items.first().thumbnailUrl }
                    if (isMusic) {
                        playlists.saveExternalMusicPlaylist(playlist.playlistId, playlist.title, "", thumbnail)
                    } else {
                        playlists.saveExternalVideoPlaylist(playlist.playlistId, playlist.title, "", thumbnail)
                    }
                    playlists.syncSavedPlaylistVideos(playlist.playlistId, items.map { it.toVideo() })
                    saved++
                }
            }
            return saved
        }

        private companion object {
            const val TAG = "YouTubeLibrarySync"
            const val PLAYLISTS = "FEplaylist_aggregation"
            const val PLAYLIST_BROWSE_PREFIX = "VL"
            const val LIKE_PAGES = 20
            const val WATCH_LATER_PAGES = 10
            const val RECENT_PAGES = 1
            const val PLAYLIST_LIST_PAGES = 5
            const val PLAYLIST_PAGES = 10
            const val MAX_PLAYLISTS = 60
            const val PARALLEL_PLAYLISTS = 4
            const val MUSIC_LEARN_LIMIT = 100
        }
    }

/**
 * Walks a browse result page by page while [onPage] says to keep going. True when the walk reached
 * the list's last page, so what was read is the whole list.
 */
internal suspend fun YouTubeAccountApi.browsePages(
    auth: YouTubeAccountApi.Auth,
    browseId: String,
    maxPages: Int,
    onPage: suspend (JsonElement) -> Boolean,
): Boolean {
    var page = browse(auth, browseId = browseId)
    repeat(maxPages) { index ->
        val keepGoing = onPage(page)
        val next = YouTubeAccountParsers.continuation(page) ?: return true
        if (!keepGoing || index == maxPages - 1) return false
        page = browse(auth, continuation = next)
    }
    return false
}
