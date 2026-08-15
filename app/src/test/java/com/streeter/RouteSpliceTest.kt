package com.streeter

import com.streeter.domain.geometry.TraceGeometry
import com.streeter.domain.model.LatLng
import com.streeter.ui.edit.RouteSplice
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behavioural spec for splicing a re-routed span into a walk's route (issue #57).
 *
 * The splice used to live behind Android's JSON inside `RouteEditViewModel`, so the most
 * intricate logic in the UI layer had no tests at all. It also chose its anchors by comparing
 * squared differences of degrees, which is not a distance: a longitude degree shrinks with the
 * cosine of latitude, so away from the equator the comparison silently picked the wrong anchor
 * and spliced the wrong span. These tests pin the span that gets replaced, and pin anchor
 * choice to a real distance.
 */
class RouteSpliceTest {
    private fun line(vararg points: LatLng): String = TraceGeometry.lineStringFeature(points.toList())

    private fun at(
        lat: Double,
        lng: Double,
    ) = LatLng(lat = lat, lng = lng)

    @Test
    fun `replaces the anchored span and keeps the rest of the route`() {
        val original = line(at(0.0, 0.0), at(0.0, 1.0), at(0.0, 2.0), at(0.0, 3.0), at(0.0, 4.0))
        val preview = line(at(1.0, 1.0), at(1.0, 3.0))

        val spliced = RouteSplice.splice(original, preview, anchor1 = at(0.0, 1.0), anchor2 = at(0.0, 3.0))

        assertEquals(
            listOf(at(0.0, 0.0), at(1.0, 1.0), at(1.0, 3.0), at(0.0, 4.0)),
            TraceGeometry.parse(spliced),
        )
    }

    @Test
    fun `anchor order does not change the result`() {
        val original = line(at(0.0, 0.0), at(0.0, 1.0), at(0.0, 2.0), at(0.0, 3.0), at(0.0, 4.0))
        val preview = line(at(1.0, 1.0), at(1.0, 3.0))

        val forwards = RouteSplice.splice(original, preview, anchor1 = at(0.0, 1.0), anchor2 = at(0.0, 3.0))
        val backwards = RouteSplice.splice(original, preview, anchor1 = at(0.0, 3.0), anchor2 = at(0.0, 1.0))

        assertEquals(TraceGeometry.parse(forwards), TraceGeometry.parse(backwards))
    }

    @Test
    fun `an anchor on a route point replaces that point`() {
        val original = line(at(0.0, 0.0), at(0.0, 1.0), at(0.0, 2.0))
        val preview = line(at(0.5, 0.5), at(0.5, 1.5))

        val spliced = RouteSplice.splice(original, preview, anchor1 = at(0.0, 1.0), anchor2 = at(0.0, 1.0))

        assertEquals(
            listOf(at(0.0, 0.0), at(0.5, 0.5), at(0.5, 1.5), at(0.0, 2.0)),
            TraceGeometry.parse(spliced),
        )
    }

    /**
     * At latitude 60 a longitude degree is about half a latitude degree, so the nearer point in
     * degrees is the further point on the ground. The eastward candidate is ~17 km from the
     * anchor and the northward one ~22 km; comparing squared degrees would pick the northward
     * one and splice from the wrong place.
     */
    @Test
    fun `chooses the anchor point by ground distance, not by degrees`() {
        val eastward = at(60.0, 0.30)
        val northward = at(60.20, 0.0)
        val original = line(eastward, northward, at(61.0, 1.0))
        val preview = line(at(60.5, 0.5), at(60.6, 0.6))
        val anchor = at(60.0, 0.0)

        val spliced = RouteSplice.splice(original, preview, anchor1 = anchor, anchor2 = anchor)

        // The eastward point is the nearer one on the ground, so it is what gets replaced;
        // comparing squared degrees would have replaced the northward one instead.
        assertEquals(
            listOf(at(60.5, 0.5), at(60.6, 0.6), northward, at(61.0, 1.0)),
            TraceGeometry.parse(spliced),
        )
    }

    @Test
    fun `spans several lines of a multi-line route`() {
        val original =
            TraceGeometry.featureCollection(
                listOf(
                    listOf(at(0.0, 0.0), at(0.0, 1.0)),
                    listOf(at(0.0, 2.0), at(0.0, 3.0)),
                ),
            )
        val preview = line(at(1.0, 1.0), at(1.0, 2.0))

        val spliced = RouteSplice.splice(original, preview, anchor1 = at(0.0, 1.0), anchor2 = at(0.0, 2.0))

        assertEquals(
            listOf(at(0.0, 0.0), at(1.0, 1.0), at(1.0, 2.0), at(0.0, 3.0)),
            TraceGeometry.parse(spliced),
        )
    }

    @Test
    fun `a malformed original leaves the preview as the whole route`() {
        val preview = line(at(1.0, 1.0), at(1.0, 2.0))

        val spliced = RouteSplice.splice("not json", preview, anchor1 = at(0.0, 0.0), anchor2 = at(0.0, 1.0))

        assertEquals(preview, spliced)
    }

    @Test
    fun `a malformed preview leaves the route as the preview`() {
        val original = line(at(0.0, 0.0), at(0.0, 1.0))

        val spliced = RouteSplice.splice(original, "not json", anchor1 = at(0.0, 0.0), anchor2 = at(0.0, 1.0))

        assertEquals("not json", spliced)
    }
}
