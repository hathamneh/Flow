package io.github.aedev.flow.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "playlists",
    indices = [Index(value = ["syncId"])],
)
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val thumbnailUrl: String,
    val isPrivate: Boolean,
    val createdAt: Long,
    val videoCount: Int = 0, // Denormalized count for performance
    val isMusic: Boolean = false,
    val isUserCreated: Boolean = true,
    val syncId: String? = null,
    @ColumnInfo(defaultValue = "0") val position: Int = 0,
    /** The YouTube playlist id this row mirrors, when it came from the signed-in account. */
    val remoteId: String? = null,
    /** True when [remoteId] is a playlist the account owns, so Flow's edits are sent back to YouTube. */
    @ColumnInfo(defaultValue = "0") val remoteOwned: Boolean = false,
)
