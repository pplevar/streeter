package com.streeter.ui.edit

import com.streeter.domain.geometry.TraceGeometry
import com.streeter.domain.model.LatLng

/**
 * Replacing an anchored span of a walk's route with a re-routed one.
 *
 * The route editor's most intricate decision — which part of the route the user's two taps
 * actually selected — and the reason it is here rather than inside `RouteEditViewModel`: on
 * [TraceGeometry] it is pure, so a JVM test can pin the span that gets replaced.
 *
 * Anchors are matched to route points by ground distance. Squared differences of degrees, which
 * this used to compare, are not a distance: a longitude degree shrinks with the cosine of
 * latitude, so away from the equator the nearer point in degrees can be the further point on the
 * ground, and the wrong span is silently replaced.
 */
object RouteSplice {
    /**
     * [originalJson] with the span between the points nearest [anchor1] and [anchor2] replaced by
     * [previewJson]. Both anchor points are themselves replaced, and the anchors may be given in
     * either order.
     *
     * The result is one line. A multi-line route is read whole rather than refused — the fix this
     * ticket is after — and comes back joined, because the rest of the app still reads a walk's
     * route as a single `Feature`: bounds fitting and the matched-distance sum would both stop
     * understanding it as a collection until they move too (issue #58).
     *
     * A route or a preview with no readable coordinates leaves nothing to splice into or with, so
     * the preview becomes the whole route — the same fallback the editor has always taken.
     */
    fun splice(
        originalJson: String,
        previewJson: String,
        anchor1: LatLng,
        anchor2: LatLng,
    ): String {
        val original = TraceGeometry.parseOrEmpty(originalJson)
        val preview = TraceGeometry.parseOrEmpty(previewJson)
        if (original.isEmpty() || preview.isEmpty()) return previewJson

        val indexA = original.nearestTo(anchor1)
        val indexB = original.nearestTo(anchor2)
        val from = minOf(indexA, indexB)
        val to = maxOf(indexA, indexB)

        return TraceGeometry.lineStringFeature(original.take(from) + preview + original.drop(to + 1))
    }

    private fun List<LatLng>.nearestTo(anchor: LatLng): Int = indices.minBy { TraceGeometry.distanceMeters(this[it], anchor) }
}
