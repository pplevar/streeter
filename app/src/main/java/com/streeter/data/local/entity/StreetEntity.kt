package com.streeter.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "streets",
    indices = [Index("osmWayId", unique = true)]
)
data class StreetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val osmWayId: Long,
    val name: String,
    val cityTotalLengthM: Double,
    val osmDataVersion: Long,
    val osmNameHash: String
)
