package io.github.aedev.flow.data.local.entity

import androidx.room.Entity

/**
 * A library change made in Flow that still has to reach the signed-in YouTube account. [key] names
 * what the change is about (a video's rating, a channel's subscription), so a newer change to the same
 * thing replaces the queued one. [payload] is the change itself, as JSON.
 */
@Entity(tableName = "youtube_outbox", primaryKeys = ["accountId", "key"])
data class YouTubeOutboxEntity(
    val accountId: String,
    val key: String,
    val payload: String,
    val enqueuedAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
)
