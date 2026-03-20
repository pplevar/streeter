package com.streeter.domain.model

data class EditOperation(
    val id: Long = 0,
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
