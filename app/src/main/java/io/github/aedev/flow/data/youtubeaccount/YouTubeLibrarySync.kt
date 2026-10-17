package io.github.aedev.flow.data.youtubeaccount

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.LikedVideoInfo
import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.recommendation.music.MusicBrainEngine
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The account's library: liked videos, liked music (YouTube Music likes), Watch later and playlists.
 * Likes and Watch later only gain entries; each YouTube playlist is kept as a saved playlist in Flow
 * under its own id and refreshed to match, private ones included, since they are read signed in.
 */
@Singleton
class YouTubeLibrarySync
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val api: YouTubeAccountApi,
        private val playlists: PlaylistRepository,
        private val musicBrain: MusicBrainEngine,
    ) {
        private val likes by lazy { LikedVideosRepository.getInstance(context) }

        data class Result(
            val likes: Int,
            val playlists: Int,
        )

        suspend fun pull(auth: YouTubeAccountApi.Auth): Result {
            val likedVideos = importLikes(auth, LIKED_VIDEOS, isMusic = false)
            val likedMusic = importLikes(auth, LIKED_MUSIC, isMusic = true)
            learnMusic(likedMusic)
            pullWatchLater(auth)
            val playlistCount = pullPlaylists(auth)
            return Result(likes = likedVideos.size + likedMusic.size, playlists = playlistCount)
        }

        private suspend fun importLikes(
            auth: YouTubeAccountApi.Auth,
            browseId: String,
            isMusic: Boolean,
        ): List<LikedVideoInfo> {
            val items = collectVideos(auth, browseId, LIKE_PAGES)
            if (items.isEmpty()) return emptyList()
            val now = System.currentTimeMillis()
            val infos =
                items.mapIndexed { index, item ->
                    LikedVideoInfo(
                        videoId = item.videoId,
                        title = item.title,
                        thumbnail = item.thumbnailUrl,
                        channelName = item.channelName,
                        likedAt = now - index * ORDER_STEP_MS,
                        isMusic = isMusic,
                        channelId = item.channelId.ifBlank { null },
                        durationSeconds = (item.durationMs / MS_PER_SECOND).toInt(),
                    )
                }
            return likes.importLikes(infos)
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

        private suspend fun pullWatchLater(auth: YouTubeAccountApi.Auth) {
            val items = collectVideos(auth, WATCH_LATER, WATCH_LATER_PAGES)
            // Flow lists Watch later newest-added first, so add YouTube's list from its end.
            items.asReversed().forEach { item ->
                if (!playlists.isInWatchLater(item.videoId)) playlists.addToWatchLater(item.toVideo())
            }
        }

        private suspend fun pullPlaylists(auth: YouTubeAccountApi.Auth): Int {
            val found = LinkedHashMap<String, RemotePlaylist>()
            api.browsePages(auth, PLAYLISTS, PLAYLIST_LIST_PAGES) { page ->
                val before = found.size
                YouTubeAccountParsers.playlists(page).forEach { found.putIfAbsent(it.playlistId, it) }
                found.size > before
            }
            var saved = 0
            found.values.take(MAX_PLAYLISTS).forEach { playlist ->
                val existing = playlists.getPlaylistEntity(playlist.playlistId)
                if (existing?.isUserCreated == true) return@forEach
                val items = collectVideos(auth, PLAYLIST_BROWSE_PREFIX + playlist.playlistId, PLAYLIST_PAGES)
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
            return saved
        }

        private suspend fun collectVideos(
            auth: YouTubeAccountApi.Auth,
            browseId: String,
            maxPages: Int,
        ): List<RemoteHistoryItem> {
            val found = LinkedHashMap<String, RemoteHistoryItem>()
            api.browsePages(auth, browseId, maxPages) { page ->
                val before = found.size
                YouTubeAccountParsers.videos(page).forEach { found.putIfAbsent(it.videoId, it) }
                found.size > before
            }
            return found.values.toList()
        }

        private fun RemoteHistoryItem.toVideo() =
            Video(
                id = videoId,
                title = title,
                channelName = channelName,
                channelId = channelId,
                thumbnailUrl = thumbnailUrl,
                duration = (durationMs / MS_PER_SECOND).toInt(),
                viewCount = 0L,
                uploadDate = "",
                isMusic = isMusic,
            )

        private companion object {
            const val TAG = "YouTubeLibrarySync"
            const val LIKED_VIDEOS = "VLLL"
            const val LIKED_MUSIC = "VLLM"
            const val WATCH_LATER = "VLWL"
            const val PLAYLISTS = "FEplaylist_aggregation"
            const val PLAYLIST_BROWSE_PREFIX = "VL"
            const val LIKE_PAGES = 20
            const val WATCH_LATER_PAGES = 10
            const val PLAYLIST_LIST_PAGES = 5
            const val PLAYLIST_PAGES = 10
            const val MAX_PLAYLISTS = 60
            const val MUSIC_LEARN_LIMIT = 100
            const val ORDER_STEP_MS = 1_000L
            const val MS_PER_SECOND = 1000L
        }
    }

/** Walks a browse result page by page while [onPage] says to keep going. */
internal suspend fun YouTubeAccountApi.browsePages(
    auth: YouTubeAccountApi.Auth,
    browseId: String,
    maxPages: Int,
    onPage: suspend (JsonElement) -> Boolean,
) {
    var page = browse(auth, browseId = browseId)
    repeat(maxPages) { index ->
        if (!onPage(page)) return
        if (index == maxPages - 1) return
        val next = YouTubeAccountParsers.continuation(page) ?: return
        page = browse(auth, continuation = next)
    }
}
