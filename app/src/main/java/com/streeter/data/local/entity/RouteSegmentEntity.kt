package com.streeter.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "route_segments",
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
data class RouteSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val walkId: Long,
    val geometryJson: String,
    val matchedWayIds: String,
    val segmentOrder: Int,
)
