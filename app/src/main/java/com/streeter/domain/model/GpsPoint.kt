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
