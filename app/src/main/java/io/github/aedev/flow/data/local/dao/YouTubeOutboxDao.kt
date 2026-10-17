package io.github.aedev.flow.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.aedev.flow.data.local.entity.YouTubeOutboxEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface YouTubeOutboxDao {
    @Upsert
    suspend fun upsert(entry: YouTubeOutboxEntity)

    @Query("DELETE FROM youtube_outbox WHERE accountId = :accountId AND `key` = :key")
    suspend fun delete(
        accountId: String,
        key: String,
    )

    /**
     * Deletes [entry] only if it was not replaced meanwhile: a change made while the old one was being
     * sent has a new time and payload, and must stay queued.
     */
    @Query(
        "DELETE FROM youtube_outbox WHERE accountId = :accountId AND `key` = :key " +
            "AND enqueuedAt = :enqueuedAt AND payload = :payload",
    )
    suspend fun deleteIfUnchanged(
        accountId: String,
        key: String,
        enqueuedAt: Long,
        payload: String,
    )

    @Query(
        "UPDATE youtube_outbox SET attempts = attempts + 1, lastError = :error " +
            "WHERE accountId = :accountId AND `key` = :key AND enqueuedAt = :enqueuedAt",
    )
    suspend fun recordFailure(
        accountId: String,
        key: String,
        enqueuedAt: Long,
        error: String,
    )

    @Query("SELECT * FROM youtube_outbox WHERE accountId = :accountId ORDER BY enqueuedAt LIMIT :limit")
    suspend fun oldest(
        accountId: String,
        limit: Int,
    ): List<YouTubeOutboxEntity>

    @Query("SELECT `key` FROM youtube_outbox WHERE accountId = :accountId")
    suspend fun keys(accountId: String): List<String>

    @Query("SELECT COUNT(*) FROM youtube_outbox WHERE accountId = :accountId")
    fun observeCount(accountId: String): Flow<Int>

    /** Drops the changes queued for accounts that were removed from this profile. */
    @Query("DELETE FROM youtube_outbox WHERE accountId NOT IN (:accountIds)")
    suspend fun deleteOtherAccounts(accountIds: List<String>)
}
