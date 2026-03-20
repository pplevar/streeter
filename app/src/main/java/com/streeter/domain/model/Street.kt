package com.streeter.domain.model

data class Street(
    val id: Long = 0,
    val osmWayId: Long,
    val name: String,
    val cityTotalLengthM: Double,
    val osmDataVersion: Long,
    val osmNameHash: String
)
