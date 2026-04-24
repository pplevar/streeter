package com.streeter.domain.model

data class MatchResult(
    val snappedPoints: List<LatLng>,
    val matchedWayIds: List<Long>,
    val routeGeometryJson: String,
    val distanceM: Double = 0.0
)
