package com.streeter

import com.streeter.domain.geometry.MalformedGeometryException
import com.streeter.domain.geometry.TraceGeometry
import com.streeter.domain.model.LatLng
import com.streeter.ui.manual.SegmentMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Behavioural spec for merging a manually created walk's routed segments (issue #57).
 *
 * A manual walk is built one routed segment at a time, each starting where the previous one
 * ended. The merge used to read those segments with Android's JSON, so it could not be tested;
 * it also only understood a single `LineString` feature, while the history list happily parsed
 * more. These tests pin the junction rule and the shapes accepted.
 */
class SegmentMergeTest {
    private fun line(vararg points: LatLng): String = TraceGeometry.lineStringFeature(points.toList())

    private fun at(
        lat: Double,
        lng: Double,
    ) = LatLng(lat = lat, lng = lng)

    @Test
    fun `joins two segments and drops the repeated junction`() {
        val first = line(at(0.0, 0.0), at(0.0, 1.0))
        val second = line(at(0.0, 1.0), at(0.0, 2.0))

        val merged = SegmentMerge.merge(listOf(first, second))

        assertEquals(
            listOf(at(0.0, 0.0), at(0.0, 1.0), at(0.0, 2.0)),
            TraceGeometry.parse(merged),
        )
    }

    @Test
    fun `drops each junction across a chain of segments`() {
        val segments =
            listOf(
                line(at(0.0, 0.0), at(0.0, 1.0)),
                line(at(0.0, 1.0), at(0.0, 2.0)),
                line(at(0.0, 2.0), at(0.0, 3.0)),
            )

        val merged = SegmentMerge.merge(segments)

        assertEquals(
            listOf(at(0.0, 0.0), at(0.0, 1.0), at(0.0, 2.0), at(0.0, 3.0)),
            TraceGeometry.parse(merged),
        )
    }

    @Test
    fun `keeps a single segment as it is`() {
        val only = line(at(0.0, 0.0), at(0.0, 1.0))

        assertEquals(only, SegmentMerge.merge(listOf(only)))
    }

    @Test
    fun `no segments is an empty route`() {
        assertEquals(TraceGeometry.EMPTY_FEATURE_COLLECTION, SegmentMerge.merge(emptyList()))
    }

    /**
     * The routing engine returns whatever shape the route needs. A multi-line segment used to
     * merge to nothing usable; every coordinate of it now takes part.
     */
    @Test
    fun `merges a multi-line segment`() {
        val first =
            TraceGeometry.featureCollection(
                listOf(
                    listOf(at(0.0, 0.0), at(0.0, 1.0)),
                    listOf(at(0.0, 1.0), at(0.0, 2.0)),
                ),
            )
        val second = line(at(0.0, 2.0), at(0.0, 3.0))

        val merged = SegmentMerge.merge(listOf(first, second))

        assertEquals(
            listOf(at(0.0, 0.0), at(0.0, 1.0), at(0.0, 1.0), at(0.0, 2.0), at(0.0, 3.0)),
            TraceGeometry.parse(merged),
        )
    }

    @Test
    fun `an empty segment contributes nothing`() {
        val first = line(at(0.0, 0.0), at(0.0, 1.0))
        val second = TraceGeometry.EMPTY_FEATURE_COLLECTION
        val third = line(at(0.0, 1.0), at(0.0, 2.0))

        val merged = SegmentMerge.merge(listOf(first, second, third))

        assertEquals(
            listOf(at(0.0, 0.0), at(0.0, 1.0), at(0.0, 2.0)),
            TraceGeometry.parse(merged),
        )
    }

    @Test
    fun `a malformed segment fails the merge rather than losing the walk's shape`() {
        val segments = listOf(line(at(0.0, 0.0), at(0.0, 1.0)), "not json")

        assertThrows(MalformedGeometryException::class.java) { SegmentMerge.merge(segments) }
    }
}
