package io.github.aedev.flow.data.youtubeaccount

import android.util.Log
import io.github.aedev.flow.data.local.LikedVideoInfo
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.recommendation.music.MusicBrainEngine
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The account's library: liked videos, liked music (YouTube Music likes), Watch later and playlists.
 * Likes and Watch later follow YouTube both ways ([YouTubeLibraryMerge]), and so do the account's own
 * playlists ([YouTubePlaylistMerge]); other people's saved playlists are kept as copies that match.
 */
@Singleton
class YouTubeLibrarySync
    @Inject
    constructor(
        private val api: YouTubeAccountApi,
        private val musicBrain: MusicBrainEngine,
        private val merge: YouTubeLibraryMerge,
        private val playlistMerge: YouTubePlaylistMerge,
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
            val playlistCount = playlistMerge.pull(auth, accountId)
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

        private companion object {
            const val TAG = "YouTubeLibrarySync"
            const val LIKE_PAGES = 20
            const val WATCH_LATER_PAGES = 10
            const val RECENT_PAGES = 1
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
