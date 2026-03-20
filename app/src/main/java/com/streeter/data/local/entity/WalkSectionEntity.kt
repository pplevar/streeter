package com.streeter.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "walk_sections",
    foreignKeys = [ForeignKey(
        entity = WalkEntity::class,
        parentColumns = ["id"],
        childColumns = ["walkId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("walkId"), Index("sectionStableId")]
)
data class WalkSectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val walkId: Long,
    val sectionStableId: String,
    val coveredPct: Float
)
