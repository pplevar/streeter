package com.streeter

import com.streeter.domain.model.GpsPoint
import com.streeter.service.GpsOutlierFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [GpsOutlierFilter].
 *
 * The filter is the first line of defence against bad GPS fixes — anything that slips through
 * ends up in the matched route and propagates into street coverage. Pay special attention to:
 *   - non-monotonic timestamps (clock skew on resume, packet reordering)
 *   - boundary at exactly maxSpeedKmh (off-by-one in the comparison would let in nonsense)
 *   - haversine accuracy for both short and long distances
 */
class GpsOutlierFilterTest {
    private fun point(
        lat: Double,
        lng: Double,
        timestampMs: Long,
    ) = GpsPoint(
        walkId = 1L,
        lat = lat,
        lng = lng,
        timestamp = timestampMs,
        accuracyM = 5f,
        speedKmh = 0f,
        isFiltered = false,
    )

    // ---------------------------------------------------------------------
    // shouldKeep — speed-based filtering
    // ---------------------------------------------------------------------

    @Test
    fun `normal walking speed is kept`() {
        // ~11 m in 20 s ≈ 2 km/h
        val p1 = point(51.5074, -0.1278, 0L)
        val p2 = point(51.5075, -0.1278, 20_000L)
        assertTrue(GpsOutlierFilter.shouldKeep(p1, p2, 50f))
    }

    @Test
    fun `impossible speed is filtered`() {
        // ~11 km in 1 s ≈ 39600 km/h
        val p1 = point(51.5074, -0.1278, 0L)
        val p2 = point(51.6074, -0.1278, 1_000L)
        assertFalse(GpsOutlierFilter.shouldKeep(p1, p2, 50f))
    }

    @Test
    fun `point with zero elapsed time is rejected`() {
        // Duplicate timestamps (same fix delivered twice, or clock didn't advance) must be
        // rejected — otherwise dividing by zero in the speed calc would yield Infinity.
        val p1 = point(51.5074, -0.1278, 1000L)
        val p2 = point(51.5075, -0.1278, 1000L)
        assertFalse(GpsOutlierFilter.shouldKeep(p1, p2, 50f))
    }

    @Test
    fun `point with negative elapsed time is rejected`() {
        // Out-of-order points (e.g. after a clock jump on resume) must be rejected: a
        // negative elapsed time would produce a negative speed and silently pass the
        // ≤ maxSpeedKmh check.
        val p1 = point(51.5074, -0.1278, 2000L)
        val p2 = point(51.5075, -0.1278, 1000L)
        assertFalse(GpsOutlierFilter.shouldKeep(p1, p2, 50f))
    }

    @Test
    fun `speed just under threshold is kept`() {
        // Threshold is inclusive (≤ maxSpeedKmh). Construct a point just under 50 km/h.
        // 50 km/h = ~13.89 m/s; pick 13 m in 1 s → ~46.8 km/h.
        val p1 = point(0.0, 0.0, 0L)
        // 13 m east at the equator ≈ 0.0001168 degrees longitude
        val p2 = point(0.0, 0.000_116_8, 1_000L)
        val dist = GpsOutlierFilter.haversineMeters(0.0, 0.0, 0.0, 0.000_116_8)
        assertTrue("distance was $dist m, expected ~13 m", dist in 12.5..13.5)
        assertTrue(GpsOutlierFilter.shouldKeep(p1, p2, 50f))
    }

    @Test
    fun `speed just over threshold is filtered`() {
        // Mirror of the test above — 15 m in 1 s = 54 km/h, just over the 50 cap.
        val p1 = point(0.0, 0.0, 0L)
        val p2 = point(0.0, 0.000_134_8, 1_000L) // ~15 m
        val dist = GpsOutlierFilter.haversineMeters(0.0, 0.0, 0.0, 0.000_134_8)
        assertTrue("distance was $dist m, expected ~15 m", dist in 14.5..15.5)
        assertFalse(GpsOutlierFilter.shouldKeep(p1, p2, 50f))
    }

    @Test
    fun `custom lower threshold filters running speed`() {
        // 5 m/s = 18 km/h (a jog). With a 10 km/h cap, this must be filtered.
        val p1 = point(0.0, 0.0, 0L)
        val p2 = point(0.0, 0.000_45, 10_000L) // ~50 m in 10 s = 18 km/h
        assertFalse(GpsOutlierFilter.shouldKeep(p1, p2, maxSpeedKmh = 10f))
        // …but kept with the default 50 km/h cap.
        assertTrue(GpsOutlierFilter.shouldKeep(p1, p2, maxSpeedKmh = 50f))
    }

    @Test
    fun `stationary points are kept`() {
        // GPS jitter while standing still still satisfies the speed bound.
        val p1 = point(51.5074, -0.1278, 0L)
        val p2 = point(51.5074, -0.1278, 5_000L)
        assertTrue(GpsOutlierFilter.shouldKeep(p1, p2, 50f))
    }

    // ---------------------------------------------------------------------
    // haversineMeters — distance accuracy
    // ---------------------------------------------------------------------

    @Test
    fun `haversine returns zero for identical points`() {
        val d = GpsOutlierFilter.haversineMeters(51.5074, -0.1278, 51.5074, -0.1278)
        assertEquals(0.0, d, 0.001)
    }

    @Test
    fun `haversine London-to-nearby-point is approximately 111m`() {
        // 0.001 degrees of latitude ≈ 111.19 m anywhere on Earth.
        val d = GpsOutlierFilter.haversineMeters(51.5074, -0.1278, 51.5084, -0.1278)
        assertEquals(111.19, d, 1.0)
    }

    @Test
    fun `haversine London-to-Paris is approximately 343km`() {
        // Sanity check for long distances — catches a wrong Earth-radius constant or
        // a confused degrees/radians conversion.
        val d = GpsOutlierFilter.haversineMeters(51.5074, -0.1278, 48.8566, 2.3522)
        // Known great-circle distance London↔Paris ≈ 343.5 km. Allow ±2 km tolerance.
        assertEquals(343_500.0, d, 2_000.0)
    }

    @Test
    fun `haversine is symmetric`() {
        val a = GpsOutlierFilter.haversineMeters(51.5074, -0.1278, 48.8566, 2.3522)
        val b = GpsOutlierFilter.haversineMeters(48.8566, 2.3522, 51.5074, -0.1278)
        assertEquals(a, b, 1e-6)
    }
}
