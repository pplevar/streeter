package com.streeter.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "gps_points",
    foreignKeys = [ForeignKey(
        entity = WalkEntity::class,
        parentColumns = ["id"],
        childColumns = ["walkId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("walkId")]
)
data class GpsPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val walkId: Long,
    val lat: Double,
    val lng: Double,
    val timestamp: Long,
    val accuracyM: Float,
    val speedKmh: Float,
    val isFiltered: Boolean
)
