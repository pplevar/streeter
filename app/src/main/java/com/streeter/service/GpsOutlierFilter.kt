package com.streeter.service

import com.streeter.domain.geometry.TraceGeometry
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.model.LatLng
import com.streeter.domain.model.toLatLng

object GpsOutlierFilter {
    /**
     * Returns true if [current] should be kept (not filtered out).
     * Filters points where the implied speed from [prev] exceeds [maxSpeedKmh]
     * (GPS glitch) or falls below [minSpeedKmh] (stationary jitter cluster).
     */
    fun shouldKeep(
        prev: GpsPoint,
        current: GpsPoint,
        maxSpeedKmh: Float = 50f,
        minSpeedKmh: Float = 0.5f,
    ): Boolean {
        val distM = TraceGeometry.distanceMeters(prev.toLatLng(), current.toLatLng())
        val elapsedS = (current.timestamp - prev.timestamp) / 1000.0
        if (elapsedS <= 0) return false
        val speedKmh = (distM / elapsedS) * 3.6
        return speedKmh in minSpeedKmh.toDouble()..maxSpeedKmh.toDouble()
    }

    /**
     * The filter's distance, in metres — now [TraceGeometry.distanceMeters] itself.
     *
     * This was the one correct copy of the haversine, so the shared module was written to match
     * it and migrating changes no result here. Kept as a name over plain latitude/longitude for
     * the callers that hold no [com.streeter.domain.model.LatLng].
     */
    fun haversineMeters(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
    ): Double = TraceGeometry.distanceMeters(LatLng(lat1, lng1), LatLng(lat2, lng2))
}
