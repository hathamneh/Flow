package io.github.aedev.flow.data.local

/**
 * Told about every playback position [ViewHistory] saves for an online video, after it is written.
 * Implementations must return at once and do their work elsewhere: saves run on the playback path.
 */
fun interface PlaybackProgressListener {
    fun onPlaybackProgress(
        videoId: String,
        positionMs: Long,
        durationMs: Long,
    )
}
