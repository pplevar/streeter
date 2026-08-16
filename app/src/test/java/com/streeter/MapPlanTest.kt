package com.streeter

import com.streeter.domain.geometry.TraceGeometry
import com.streeter.domain.model.GpsPoint
import com.streeter.ui.map.MapLayer
import com.streeter.ui.map.MapSlot
import com.streeter.ui.map.mapPlanOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral spec for what a screen's layer declaration means for the map (issue #63).
 *
 * The assembly used to live inside `MapLibreMapView`, reachable only from an instrumented test
 * with a renderer attached. It is a value now, so what each screen asks to be drawn — and in
 * what order — can be read here.
 */
class MapPlanTest {
    private fun point(
        id: Long,
        lat: Double,
        lng: Double,
        filtered: Boolean = false,
    ) = GpsPoint(
        id = id,
        walkId = 1,
        lat = lat,
        lng = lng,
        timestamp = id,
        accuracyM = 5f,
        speedKmh = 4f,
        isFiltered = filtered,
    )

    private val empty = TraceGeometry.EMPTY_FEATURE_COLLECTION

    // --- Draw order -----------------------------------------------------------------------

    @Test
    fun `past walks are drawn beneath the live route`() {
        // ADR-0006: history is context, never something that covers the walk in hand.
        val order = MapSlot.entries.map { it.name }

        assertTrue(order.indexOf("HISTORY") < order.indexOf("TRACE"))
    }

    @Test
    fun `the selection sits above every line and every dot`() {
        val order = MapSlot.entries
        assertEquals(MapSlot.SELECTED_POINT, order.last())
        assertTrue(order.indexOf(MapSlot.TRACE_POINTS) > order.indexOf(MapSlot.TRACE))
    }

    @Test
    fun `an uncommitted edit is drawn over the route it would replace`() {
        val order = MapSlot.entries
        assertTrue(order.indexOf(MapSlot.ROUTE_PREVIEW) > order.indexOf(MapSlot.MATCHED_ROUTE))
        assertTrue(order.indexOf(MapSlot.HIGHLIGHTED_WALK) > order.indexOf(MapSlot.MATCHED_ROUTE))
    }

    // --- Undeclared slots -----------------------------------------------------------------

    @Test
    fun `a screen that declares nothing draws nothing anywhere`() {
        val plan = mapPlanOf(emptyList())

        assertEquals(MapSlot.entries.toSet(), plan.payloads.keys)
        MapSlot.entries.forEach { assertEquals(empty, plan.payloadFor(it)) }
        assertNull(plan.onPointTap)
    }

    @Test
    fun `a slot a screen stops declaring is cleared rather than left stale`() {
        val before = mapPlanOf(listOf(MapLayer.MatchedRoute("""{"type":"Feature","geometry":null,"properties":{}}""")))
        val after = mapPlanOf(listOf(MapLayer.Trace(listOf(point(1, 1.0, 2.0), point(2, 1.1, 2.1)))))

        assertTrue(before.payloadFor(MapSlot.MATCHED_ROUTE) != empty)
        assertEquals(empty, after.payloadFor(MapSlot.MATCHED_ROUTE))
    }

    // --- The trace ------------------------------------------------------------------------

    @Test
    fun `the trace is drawn without its Outlier Points`() {
        val plan =
            mapPlanOf(
                listOf(
                    MapLayer.Trace(
                        listOf(
                            point(1, 55.0, 37.0),
                            point(2, 99.0, 99.0, filtered = true),
                            point(3, 55.1, 37.1),
                        ),
                    ),
                ),
            )

        val line = TraceGeometry.parse(plan.payloadFor(MapSlot.TRACE))
        assertEquals(2, line.size)
        assertEquals(55.0, line[0].lat, 1e-9)
        assertEquals(55.1, line[1].lat, 1e-9)
    }

    @Test
    fun `a single point is no line`() {
        val plan = mapPlanOf(listOf(MapLayer.Trace(listOf(point(1, 55.0, 37.0)))))

        assertEquals(empty, plan.payloadFor(MapSlot.TRACE))
    }

    // --- The live position ----------------------------------------------------------------

    @Test
    fun `the position dot follows the newest observation that was kept`() {
        val plan =
            mapPlanOf(
                listOf(
                    MapLayer.CurrentPosition(
                        listOf(
                            point(1, 55.0, 37.0),
                            point(2, 55.5, 37.5),
                            point(3, 99.0, 99.0, filtered = true),
                        ),
                    ),
                ),
            )

        val dot = TraceGeometry.parse(plan.payloadFor(MapSlot.CURRENT_POSITION))
        assertEquals(1, dot.size)
        assertEquals(55.5, dot[0].lat, 1e-9)
    }

    @Test
    fun `a walk with nothing recorded yet has no position dot`() {
        assertEquals(empty, mapPlanOf(listOf(MapLayer.CurrentPosition(emptyList()))).payloadFor(MapSlot.CURRENT_POSITION))
    }

    // --- The points editor ----------------------------------------------------------------

    @Test
    fun `every drawn dot carries the id a tap is answered with`() {
        val plan = mapPlanOf(listOf(MapLayer.TracePoints(listOf(point(7, 55.0, 37.0), point(9, 55.1, 37.1)))))

        val payload = plan.payloadFor(MapSlot.TRACE_POINTS)
        assertTrue(payload.contains(""""pointId":7"""))
        assertTrue(payload.contains(""""pointId":9"""))
        assertEquals(2, TraceGeometry.parse(payload).size)
    }

    @Test
    fun `the selected point is its own layer, and nothing selected draws nothing`() {
        val points = listOf(point(7, 55.0, 37.0), point(9, 55.1, 37.1))

        val selected = mapPlanOf(listOf(MapLayer.TracePoints(points, selected = points[1])))
        assertEquals(55.1, TraceGeometry.parse(selected.payloadFor(MapSlot.SELECTED_POINT))[0].lat, 1e-9)

        val none = mapPlanOf(listOf(MapLayer.TracePoints(points)))
        assertEquals(empty, none.payloadFor(MapSlot.SELECTED_POINT))
    }

    @Test
    fun `the tap callback travels with the layer that declared the dots`() {
        var tapped: Long? = null
        val plan = mapPlanOf(listOf(MapLayer.TracePoints(listOf(point(7, 55.0, 37.0)), onTap = { tapped = it })))

        plan.onPointTap?.invoke(7L)

        assertEquals(7L, tapped)
    }

    // --- The two concepts that used to share a slot ---------------------------------------

    private fun lineAt(
        lat: Double,
        lng: Double,
    ) = """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[$lng,$lat],[$lng,$lat]]},"properties":{}}"""

    @Test
    fun `a highlighted walk and an uncommitted edit are different layers`() {
        val plan =
            mapPlanOf(
                listOf(
                    MapLayer.HighlightedWalk(lineAt(55.0, 37.0)),
                    MapLayer.RoutePreview(lineAt(56.0, 38.0)),
                ),
            )

        assertEquals(55.0, TraceGeometry.parse(plan.payloadFor(MapSlot.HIGHLIGHTED_WALK))[0].lat, 1e-9)
        assertEquals(56.0, TraceGeometry.parse(plan.payloadFor(MapSlot.ROUTE_PREVIEW))[0].lat, 1e-9)
    }

    @Test
    fun `a layer declared with no geometry yet draws nothing`() {
        val plan = mapPlanOf(listOf(MapLayer.MatchedRoute(null), MapLayer.TraceHistory(null)))

        assertEquals(empty, plan.payloadFor(MapSlot.MATCHED_ROUTE))
        assertEquals(empty, plan.payloadFor(MapSlot.HISTORY))
    }

    @Test
    fun `each slot has its own source`() {
        assertEquals(MapSlot.entries.size, MapSlot.entries.map { it.sourceId }.toSet().size)
    }
}
