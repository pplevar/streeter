package com.streeter.ui.map

import com.streeter.domain.geometry.TraceGeometry
import com.streeter.domain.model.GpsPoint
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

    /** A route the screen already holds as GeoJSON: a matched route, or streets drawn together. */
    MATCHED_ROUTE,

    /** One walk singled out of what [MATCHED_ROUTE] draws. */
    HIGHLIGHTED_WALK,

    /** An edit the user has not committed yet, over the route it would replace. */
    ROUTE_PREVIEW,

    /** One tappable dot per trace point. */
    TRACE_POINTS,

    /** The dot the user has selected, and its halo. */
    SELECTED_POINT,
    ;

    val sourceId: String get() = "${name.lowercase()}_source"
}

/**
 * What a screen wants drawn, named in the app's own terms rather than after a renderer slot.
 *
 * A screen lists the layers it wants and leaves out the rest; there is no opting out by passing
 * null or an empty list to a parameter it does not use.
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

    /** A route the screen holds as GeoJSON: one walk's matched route, or a street's walks combined. */
    data class MatchedRoute(
        val geoJson: String?,
    ) : MapLayer

    /**
     * One walk drawn out of the many that [MatchedRoute] shows — the street screen's selection.
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
 * What the renderer should be holding: one payload per slot, plus the callbacks the declared
 * layers brought with them.
 *
 * Every slot is present — a slot no screen asked for carries an empty payload rather than being
 * absent — so applying a plan also clears whatever the previous plan drew there.
 */
data class MapPlan(
    val payloads: Map<MapSlot, String>,
    val onPointTap: ((Long?) -> Unit)? = null,
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
    for (layer in layers) {
        when (layer) {
            is MapLayer.Trace -> payloads[MapSlot.TRACE] = traceLine(layer.points)
            is MapLayer.TraceHistory -> payloads[MapSlot.HISTORY] = layer.geoJson.orEmptyCollection()
            is MapLayer.CurrentPosition -> payloads[MapSlot.CURRENT_POSITION] = currentPosition(layer.points)
            is MapLayer.MatchedRoute -> payloads[MapSlot.MATCHED_ROUTE] = layer.geoJson.orEmptyCollection()
            is MapLayer.HighlightedWalk -> payloads[MapSlot.HIGHLIGHTED_WALK] = layer.geoJson.orEmptyCollection()
            is MapLayer.RoutePreview -> payloads[MapSlot.ROUTE_PREVIEW] = layer.geoJson.orEmptyCollection()
            is MapLayer.TracePoints -> {
                payloads[MapSlot.TRACE_POINTS] = pointDots(layer.points)
                payloads[MapSlot.SELECTED_POINT] = layer.selected?.let(::pointFeature).orEmptyCollection()
                onPointTap = layer.onTap
            }
        }
    }
    return MapPlan(payloads = payloads, onPointTap = onPointTap)
}

private fun String?.orEmptyCollection(): String = this ?: TraceGeometry.EMPTY_FEATURE_COLLECTION

private fun traceLine(points: List<GpsPoint>): String {
    val drawn = points.filter { !it.isFiltered }
    if (drawn.size < 2) return TraceGeometry.EMPTY_FEATURE_COLLECTION
    return TraceGeometry.lineStringFeature(drawn.map { it.toLatLng() })
}

private fun currentPosition(points: List<GpsPoint>): String =
    points.lastOrNull { !it.isFiltered }?.let(::pointFeature) ?: TraceGeometry.EMPTY_FEATURE_COLLECTION

private fun pointDots(points: List<GpsPoint>): String {
    val features = points.filter { !it.isFiltered }.joinToString(",") { pointFeature(it, """"$POINT_ID_PROPERTY":${it.id}""") }
    return if (features.isEmpty()) TraceGeometry.EMPTY_FEATURE_COLLECTION else """{"type":"FeatureCollection","features":[$features]}"""
}

private fun pointFeature(
    point: GpsPoint,
    properties: String = "",
): String =
    """{"type":"Feature","geometry":{"type":"Point","coordinates":[${point.lng},${point.lat}]},""" +
        """"properties":{$properties}}"""
