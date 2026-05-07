package com.streeter.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class GpsPointDto(
    val lat: Double,
    val lng: Double,
    val timestamp: Long,
    val accuracyM: Float,
    val speedKmh: Float,
    val isFiltered: Boolean,
    val isManual: Boolean = false,
)
