package com.streeter.domain.model

data class RouteResult(
    val geometryJson: String,
    val distanceM: Double,
    val wayIds: List<Long>
)
