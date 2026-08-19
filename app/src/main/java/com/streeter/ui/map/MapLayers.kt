package com.streeter.ui.map

import com.streeter.domain.geometry.TraceGeometry
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.model.LatLng
import com.streeter.domain.model.toLatLng

/**
 * A slot in the map's style: one GeoJSON source and whatever the renderer draws from it.
 *
 * Declaration order is draw order, bottom to top. It is fixed here rather than at each call
 * site so two screens can never disagree about what covers what — in particular the past-walks
 * layer stays beneath the live route (ADR-0006).
 */
enum class MapSlot {
    /** Other walks' GPS Traces, subordinate to the walk in hand. */
    HISTORY,

    /** The walk's own GPS Trace. */
    TRACE,

    /** The head of a live trace — where the walker is now. */
    CURRENT_POSITION,

    /** A walk's route: matched, drawn by hand, or several walks' routes shown together. */
    ROUTE,

    /** One walk singled out of what [ROUTE] draws. */
    HIGHLIGHTED_WALK,

    /** An edit the user has not committed yet, over the route it would replace. */
    ROUTE_PREVIEW,

    /** An uncommitted move of one trace point, over the trace it would replace. */
    TRACE_PREVIEW,

    /** One tappable dot per trace point. */
    TRACE_POINTS,

    /** Where a point being moved started out, while its move is uncommitted. */
    EDIT_ORIGIN,

    /** The dot the user has selected, and its halo. */
    SELECTED_POINT,
    ;

    val sourceId: String get() = "${name.lowercase()}_source"
}

/**
 * What a screen wants drawn, named in the app's own terms rather than after a renderer slot.
 *
 * A screen lists the layers it wants and leaves out the rest. A layer whose geometry has not
 * loaded yet is still declared, with a null payload: "this screen draws a route, which is not
 * ready" is a different statement from "this screen has no route layer", and only the first is
 * true while a walk is loading.
 */
sealed interface MapLayer {
    /** The walk's own GPS Trace, as a line. Outlier Points are not drawn (see CONTEXT.md). */
    data class Trace(
        val points: List<GpsPoint>,
    ) : MapLayer

    /** Other walks' GPS Traces, already assembled as GeoJSON. Drawn beneath [Trace] (ADR-0006). */
    data class TraceHistory(
        val geoJson: String?,
    ) : MapLayer

    /** A dot at the newest observation of [points] — where the walker is right now. */
    data class CurrentPosition(
        val points: List<GpsPoint>,
    ) : MapLayer

    /**
     * A route the screen holds as GeoJSON: one walk's matched route, a Manual Walk's routed
     * path, or every walk that covered a street drawn together.
     */
    data class Route(
        val geoJson: String?,
    ) : MapLayer

    /**
     * One walk drawn out of the many that [Route] shows — the street screen's selection.
     *
     * Distinct from [RoutePreview] even though both sit above the route: this is a highlight of
     * something already saved, not an edit awaiting a decision.
     */
    data class HighlightedWalk(
        val geoJson: String?,
    ) : MapLayer

    /** The route editor's uncommitted edit, over the route it would replace. */
    data class RoutePreview(
        val geoJson: String?,
    ) : MapLayer

    /**
     * The uncommitted shape of a trace whose point is being moved: the segments joining that
     * point, at the coordinate it would take, to the neighbours it still has.
     *
     * Distinct from [RoutePreview], which previews an edit to a *route*. Both say "not committed
     * yet" and both sit above what they would replace, but they are edits to different things
     * and each screen must be able to restyle its own.
     */
    data class TracePreview(
        val line: List<LatLng>,
    ) : MapLayer

    /**
     * A ghost at [origin] — where a point being moved was before the move began, so the user can
     * see how far they have taken it. Null while nothing is being moved.
     */
    data class EditOrigin(
        val origin: LatLng?,
    ) : MapLayer

    /**
     * Every point of the trace as a tappable dot, with [selected] marked.
     *
     * [onTap] is answered with the tapped point's id, or null when the tap hit no dot.
     */
    data class TracePoints(
        val points: List<GpsPoint>,
        val selected: GpsPoint? = null,
        val onTap: (Long?) -> Unit = {},
    ) : MapLayer
}

/**
 * What the renderer should be holding: one payload per slot, plus what the declared layers said
 * about the camera and about taps.
 *
 * Every slot is present — a slot no screen asked for carries an empty payload rather than being
 * absent — so applying a plan also clears whatever the previous plan drew there.
 */
data class MapPlan(
    val payloads: Map<MapSlot, String>,
    val onPointTap: ((Long?) -> Unit)? = null,
    /**
     * Where a followed camera belongs: the head of the walk being recorded. Read from the
     * points rather than from a payload, so following never re-parses what was just written.
     */
    val followTarget: LatLng? = null,
    /**
     * True when the screen has no walk of its own to show yet — so a screen that opens at a
     * place rather than at a route may still centre itself. Other walks drawn as history are
     * context, not a walk of one's own, and do not count.
     */
    val hasNoWalkYet: Boolean = true,
) {
    fun payloadFor(slot: MapSlot): String = payloads.getValue(slot)
}

/** The property each trace dot carries, so a tap can be answered with an id rather than a coordinate. */
const val POINT_ID_PROPERTY = "pointId"

/**
 * The plan a list of declared [layers] amounts to.
 *
 * Pure and Android-free: the whole of what a screen's declaration means for the map is decided
 * here, where a JVM test can read it, leaving the view with nothing but the renderer calls.
 * A slot declared twice takes the last declaration, as the last writer would have won anyway.
 */
fun mapPlanOf(layers: List<MapLayer>): MapPlan {
    val payloads = MapSlot.entries.associateWith { TraceGeometry.EMPTY_FEATURE_COLLECTION }.toMutableMap()
    var onPointTap: ((Long?) -> Unit)? = null
    var traceHead: LatLng? = null
    var positionHead: LatLng? = null
    var hasNoWalkYet = true
    for (layer in layers) {
        when (layer) {
            is MapLayer.Trace -> {
                val drawn = layer.points.drawable()
                payloads[MapSlot.TRACE] = traceLine(drawn)
                traceHead = drawn.lastOrNull()?.toLatLng()
                hasNoWalkYet = hasNoWalkYet && drawn.isEmpty()
            }
            is MapLayer.TraceHistory -> payloads[MapSlot.HISTORY] = layer.geoJson.orEmptyCollection()
            is MapLayer.CurrentPosition -> {
                val head = layer.points.drawable().lastOrNull()
                payloads[MapSlot.CURRENT_POSITION] = head?.let { TraceGeometry.pointFeature(it.toLatLng()) }.orEmptyCollection()
                positionHead = head?.toLatLng()
                hasNoWalkYet = hasNoWalkYet && head == null
            }
            is MapLayer.Route -> {
                payloads[MapSlot.ROUTE] = layer.geoJson.orEmptyCollection()
                hasNoWalkYet = hasNoWalkYet && layer.geoJson == null
            }
            is MapLayer.HighlightedWalk -> payloads[MapSlot.HIGHLIGHTED_WALK] = layer.geoJson.orEmptyCollection()
            is MapLayer.RoutePreview -> payloads[MapSlot.ROUTE_PREVIEW] = layer.geoJson.orEmptyCollection()
            is MapLayer.TracePreview ->
                payloads[MapSlot.TRACE_PREVIEW] =
                    if (layer.line.size < 2) {
                        TraceGeometry.EMPTY_FEATURE_COLLECTION
                    } else {
                        TraceGeometry.lineStringFeature(layer.line)
                    }
            is MapLayer.EditOrigin ->
                payloads[MapSlot.EDIT_ORIGIN] = layer.origin?.let { TraceGeometry.pointFeature(it) }.orEmptyCollection()
            is MapLayer.TracePoints -> {
                val drawn = layer.points.drawable()
                payloads[MapSlot.TRACE_POINTS] = TraceGeometry.collect(drawn.map { it.dotFeature() })
                payloads[MapSlot.SELECTED_POINT] =
                    layer.selected?.let { TraceGeometry.pointFeature(it.toLatLng()) }.orEmptyCollection()
                onPointTap = layer.onTap
                hasNoWalkYet = hasNoWalkYet && drawn.isEmpty()
            }
        }
    }
    return MapPlan(
        payloads = payloads,
        onPointTap = onPointTap,
        // The position dot is where the walker is; the head of the trace is the best stand-in
        // for a screen that draws the line without the dot.
        followTarget = positionHead ?: traceHead,
        hasNoWalkYet = hasNoWalkYet,
    )
}

/** The observations a map may draw: an Outlier Point is not part of the trace (see CONTEXT.md). */
private fun List<GpsPoint>.drawable(): List<GpsPoint> = filter { !it.isFiltered }

private fun String?.orEmptyCollection(): String = this ?: TraceGeometry.EMPTY_FEATURE_COLLECTION

private fun traceLine(drawn: List<GpsPoint>): String =
    if (drawn.size < 2) {
        TraceGeometry.EMPTY_FEATURE_COLLECTION
    } else {
        TraceGeometry.lineStringFeature(drawn.map { it.toLatLng() })
    }

private fun GpsPoint.dotFeature(): String = TraceGeometry.pointFeature(toLatLng(), mapOf(POINT_ID_PROPERTY to id))
