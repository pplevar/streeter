package com.streeter

import com.streeter.domain.model.GpsPoint
import com.streeter.domain.model.LatLng
import com.streeter.ui.editpoints.editPreviewLine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behavioral spec for the points editor's live move preview (issue #76).
 *
 * The preview joins the point being moved, at the coordinate it would take, to the neighbours
 * it still has. What makes this worth a test of its own is the ends of the trace: a point with
 * only one neighbour has only one segment, and a two-point walk is nothing but ends. Those are
 * the cases that look fine in review and draw a phantom segment on a device.
 */
class EditPreviewTest {
    private fun point(
        id: Long,
        lat: Double,
        lng: Double,
    ) = GpsPoint(
        id = id,
        walkId = 1L,
        lat = lat,
        lng = lng,
        timestamp = id,
        accuracyM = 5f,
        speedKmh = 0f,
        isFiltered = false,
    )

    private val trace =
        listOf(
            point(1, 0.0, 0.0),
            point(2, 1.0, 1.0),
            point(3, 2.0, 2.0),
        )

    @Test
    fun `a point in the middle of the trace previews both of its segments`() {
        val line = editPreviewLine(trace, editingPointId = 2L, pending = LatLng(9.0, 9.0))

        assertEquals(listOf(LatLng(0.0, 0.0), LatLng(9.0, 9.0), LatLng(2.0, 2.0)), line)
    }

    @Test
    fun `the first point has no preceding segment to preview`() {
        val line = editPreviewLine(trace, editingPointId = 1L, pending = LatLng(9.0, 9.0))

        assertEquals(listOf(LatLng(9.0, 9.0), LatLng(1.0, 1.0)), line)
    }

    @Test
    fun `the last point has no following segment to preview`() {
        val line = editPreviewLine(trace, editingPointId = 3L, pending = LatLng(9.0, 9.0))

        assertEquals(listOf(LatLng(1.0, 1.0), LatLng(9.0, 9.0)), line)
    }

    @Test
    fun `on a two-point walk each point previews the single segment between them`() {
        val pair = listOf(point(1, 0.0, 0.0), point(2, 1.0, 1.0))

        assertEquals(
            listOf(LatLng(9.0, 9.0), LatLng(1.0, 1.0)),
            editPreviewLine(pair, editingPointId = 1L, pending = LatLng(9.0, 9.0)),
        )
        assertEquals(
            listOf(LatLng(0.0, 0.0), LatLng(9.0, 9.0)),
            editPreviewLine(pair, editingPointId = 2L, pending = LatLng(9.0, 9.0)),
        )
    }

    @Test
    fun `a coarse-zoom swing is previewed at whatever size the user made it`() {
        // No clamp and no minimum-zoom guard: the preview's job is to make a wild move visible
        // while it is still being chosen, not to prevent it.
        val line = editPreviewLine(trace, editingPointId = 2L, pending = LatLng(50.0, -30.0))

        assertEquals(listOf(LatLng(0.0, 0.0), LatLng(50.0, -30.0), LatLng(2.0, 2.0)), line)
    }

    @Test
    fun `a point that is not in the trace previews nothing`() {
        assertEquals(emptyList<LatLng>(), editPreviewLine(trace, editingPointId = 99L, pending = LatLng(9.0, 9.0)))
    }

    @Test
    fun `a lone point has no neighbour to draw a segment to`() {
        val single = listOf(point(1, 0.0, 0.0))

        assertEquals(emptyList<LatLng>(), editPreviewLine(single, editingPointId = 1L, pending = LatLng(9.0, 9.0)))
    }
}
