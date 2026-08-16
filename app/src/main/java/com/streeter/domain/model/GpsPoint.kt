package com.streeter.domain.model

data class GpsPoint(
    val id: Long = 0,
    val walkId: Long,
    val lat: Double,
    val lng: Double,
    val timestamp: Long,
    val accuracyM: Float,
    val speedKmh: Float,
    val isFiltered: Boolean,
    val isManual: Boolean = false,
)

/** Where the observation was, without the rest of what was recorded about it. */
fun GpsPoint.toLatLng(): LatLng = LatLng(lat = lat, lng = lng)
