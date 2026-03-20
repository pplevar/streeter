package com.streeter.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "walks")
data class WalkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String?,
    val date: Long,
    val durationMs: Long,
    val distanceM: Double,
    val status: String,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long
)
