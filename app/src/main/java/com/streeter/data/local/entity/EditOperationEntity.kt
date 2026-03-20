package com.streeter.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "edit_operations",
    foreignKeys = [ForeignKey(
        entity = WalkEntity::class,
        parentColumns = ["id"],
        childColumns = ["walkId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("walkId")]
)
data class EditOperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val walkId: Long,
    val operationOrder: Int,
    val anchor1Lat: Double,
    val anchor1Lng: Double,
    val anchor2Lat: Double,
    val anchor2Lng: Double,
    val waypointLat: Double,
    val waypointLng: Double,
    val replacedGeometryJson: String,
    val newGeometryJson: String,
    val createdAt: Long
)
