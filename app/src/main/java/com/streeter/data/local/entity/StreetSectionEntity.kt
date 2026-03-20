package com.streeter.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "street_sections",
    foreignKeys = [ForeignKey(
        entity = StreetEntity::class,
        parentColumns = ["id"],
        childColumns = ["streetId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("streetId"), Index("stableId", unique = true)]
)
data class StreetSectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val streetId: Long,
    val fromNodeOsmId: Long,
    val toNodeOsmId: Long,
    val lengthM: Double,
    val geometryJson: String,
    val stableId: String,
    val isOrphaned: Boolean = false
)
