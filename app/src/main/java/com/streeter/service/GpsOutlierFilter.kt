package com.streeter.service

import com.streeter.domain.model.GpsPoint
import kotlin.math.*

object GpsOutlierFilter {

    /**
     * Returns true if [current] should be kept (not filtered out).
     * Filters points where the implied speed from [prev] exceeds [maxSpeedKmh].
     */
    fun shouldKeep(prev: GpsPoint, current: GpsPoint, maxSpeedKmh: Float = 50f): Boolean {
        val distM = haversineMeters(prev.lat, prev.lng, current.lat, current.lng)
        val elapsedS = (current.timestamp - prev.timestamp) / 1000.0
        if (elapsedS <= 0) return false
        val speedKmh = (distM / elapsedS) * 3.6
        return speedKmh <= maxSpeedKmh
    }

    fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
