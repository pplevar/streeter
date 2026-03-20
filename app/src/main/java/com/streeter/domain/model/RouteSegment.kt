package com.streeter.domain.model

data class RouteSegment(
    val id: Long = 0,
    val walkId: Long,
    val geometryJson: String,
    val matchedWayIds: String,
    val segmentOrder: Int
)
