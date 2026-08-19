package com.streeter.ui.editpoints

import com.streeter.domain.model.GpsPoint
import com.streeter.domain.model.LatLng
import com.streeter.domain.model.toLatLng

/**
 * The map edges covered by screen chrome — the bottom sheet, the control pill, the top bar —
 * in pixels. What is left over is the *uncovered region*: the part of the map the user can
 * actually see.
 */
data class MapInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
)

/** How far the selected point must move on screen, in pixels, to become visible. */
data class PointPan(
    val dxPx: Float,
    val dyPx: Float,
)

/**
 * Decides the camera move for a selection: selection highlights, it does not navigate.
 *
 * Returns `null` — meaning "leave the camera alone" — whenever the selected point already
 * falls inside the uncovered region, so stepping along a trace holds the user's framing still.
 * When the point is outside that region (off the viewport, or inside it but underneath the
 * sheet or pill), returns the smallest pan that brings it just inside, keeping [marginPx] of
 * breathing room from the region's edges. Zoom is never part of the answer.
 *
 * Pure and Android-free: the composable turns the result into a camera target itself.
 */
fun panToReveal(
    pointXPx: Float,
    pointYPx: Float,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    insets: MapInsets,
    marginPx: Float = 0f,
): PointPan? {
    val dx = shiftInto(pointXPx, insets.left + marginPx, viewportWidthPx - insets.right - marginPx)
    val dy = shiftInto(pointYPx, insets.top + marginPx, viewportHeightPx - insets.bottom - marginPx)
    return if (dx == 0f && dy == 0f) null else PointPan(dx, dy)
}

/**
 * How far [value] must move to land inside `[min, max]`, or 0 if it already is. A region the
 * chrome has squeezed shut (`max <= min`) aims at its midpoint rather than giving up, so a
 * selection is never left unreachable on a very short screen.
 */
private fun shiftInto(
    value: Float,
    min: Float,
    max: Float,
): Float =
    when {
        max <= min -> (min + max) / 2f - value
        value < min -> min - value
        value > max -> max - value
        else -> 0f
    }

/**
 * The line the live preview draws while a point is being moved: the segments joining the edited
 * point, at its uncommitted coordinate, to the neighbours it still has in the trace.
 *
 * Returned as one polyline rather than two segments — the two share the moving point, so drawn
 * together they are the corrected shape of the walk. At the ends of the trace only one segment
 * exists, and the result is that segment alone; a point with no neighbours at all (or an id the
 * trace does not hold) previews nothing.
 *
 * Pure and Android-free, beside [panToReveal], so the geometry that decides whether the preview
 * is right at the first point, the last point and on a two-point walk is settled under a JVM
 * test rather than on a device.
 */
fun editPreviewLine(
    points: List<GpsPoint>,
    editingPointId: Long,
    pending: LatLng,
): List<LatLng> {
    val index = points.indexOfFirst { it.id == editingPointId }
    if (index < 0) return emptyList()
    val line = mutableListOf<LatLng>()
    points.getOrNull(index - 1)?.let { line += it.toLatLng() }
    line += pending
    points.getOrNull(index + 1)?.let { line += it.toLatLng() }
    return if (line.size < 2) emptyList() else line
}
