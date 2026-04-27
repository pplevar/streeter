package com.streeter.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "walk_streets",
    foreignKeys = [
        ForeignKey(
            entity = WalkEntity::class,
            parentColumns = ["id"],
            childColumns = ["walkId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("walkId"), Index("streetId")],
)
data class WalkStreetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val walkId: Long,
    val streetId: Long,
    val coveragePct: Float,
    val walkedLengthM: Double = 0.0,
)
