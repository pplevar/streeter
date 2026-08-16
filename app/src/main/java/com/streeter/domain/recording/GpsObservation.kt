package com.streeter.domain.recording

/**
 * One raw GPS fix as it arrives from the platform, before the recording session decides which
 * walk it belongs to and whether it is an Outlier Point.
 */
data class GpsObservation(
    val lat: Double,
    val lng: Double,
    val timestamp: Long,
    val accuracyM: Float,
    val speedKmh: Float,
)
