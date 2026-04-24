package com.streeter.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pending_match_jobs",
    foreignKeys = [
        ForeignKey(
            entity = WalkEntity::class,
            parentColumns = ["id"],
            childColumns = ["walkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("walkId")],
)
data class PendingMatchJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val walkId: Long,
    val queuedAt: Long,
    val status: String,
    val retryCount: Int = 0,
    val lastError: String? = null,
)
