package com.streeter

import com.streeter.domain.model.GpsPoint
import com.streeter.service.GpsOutlierFilter
import org.junit.Assert.*
import org.junit.Test

class GpsOutlierFilterTest {

    private fun makePoint(lat: Double, lng: Double, timestampMs: Long) = GpsPoint(
        walkId = 1L, lat = lat, lng = lng, timestamp = timestampMs,
        accuracyM = 5f, speedKmh = 0f, isFiltered = false
    )

    @Test
    fun `normal walking speed is kept`() {
        val p1 = makePoint(51.5074, -0.1278, 0L)
        val p2 = makePoint(51.5075, -0.1278, 20_000L) // ~11 m in 20 s ≈ 2 km/h
        assertTrue(GpsOutlierFilter.shouldKeep(p1, p2, 50f))
    }

    @Test
    fun `impossible speed is filtered`() {
        val p1 = makePoint(51.5074, -0.1278, 0L)
        val p2 = makePoint(51.6074, -0.1278, 1_000L) // ~11 km in 1 s = 39600 km/h
        assertFalse(GpsOutlierFilter.shouldKeep(p1, p2, 50f))
    }

    @Test
    fun `haversine distance London to nearby point`() {
        val dist = GpsOutlierFilter.haversineMeters(51.5074, -0.1278, 51.5084, -0.1278)
        assertTrue("Expected ~111m, got $dist", dist in 100.0..130.0)
    }
}
