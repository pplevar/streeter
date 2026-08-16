package com.streeter.service

import com.streeter.domain.geometry.TraceGeometry
import com.streeter.domain.model.GpsPoint
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
}
