package com.streeter.ui.map

import android.graphics.PointF
import android.graphics.RectF
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.streeter.domain.geometry.TraceGeometry
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.model.toLatLng
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import timber.log.Timber

val MAP_STYLE_URL =
    """
    {
      "version": 8,
      "sources": {
        "osm": {
          "type": "raster",
          "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
          "tileSize": 256,
          "attribution": "\u00a9 OpenStreetMap contributors",
          "maxzoom": 19
        }
      },
      "layers": [{
        "id": "osm-tiles",
        "type": "raster",
        "source": "osm"
      }]
    }
    """.trimIndent()

private const val GPS_ROUTE_SOURCE = "gps_route_source"
private const val GPS_ROUTE_LAYER = "gps_route_layer"
private const val HISTORY_SOURCE = "history_source"
private const val HISTORY_LAYER = "history_layer"
private const val POSITION_SOURCE = "position_source"
private const val POSITION_LAYER = "position_layer"
private const val ROUTE_JSON_SOURCE = "route_json_source"
private const val ROUTE_JSON_LAYER = "route_json_layer"
private const val PREVIEW_SOURCE = "preview_source"
private const val PREVIEW_LAYER = "preview_layer"
private const val POINT_DOTS_SOURCE = "point_dots_source"
private const val POINT_DOTS_LAYER = "point_dots_layer"
private const val POINT_ID_PROPERTY = "pointId"

/** Breathing room left around a fitted route, so its ends do not sit against the screen edge. */
private const val BOUNDS_PADDING_PX = 64

/** Half-width of the hit-test box around a tap — a fingertip's worth of slack around a dot. */
private val TAP_RADIUS = 20.dp
private const val SELECTED_POINT_SOURCE = "selected_point_source"
private const val SELECTED_POINT_HALO_LAYER = "selected_point_halo_layer"
private const val SELECTED_POINT_LAYER = "selected_point_layer"

@Suppress("DEPRECATION") // LocalLifecycleOwner: lifecycle-runtime-compose not yet in deps
@Composable
fun MapLibreMapView(
    modifier: Modifier = Modifier,
    styleUrl: String,
    gpsPoints: List<GpsPoint> = emptyList(),
    routeGeometryJson: String? = null,
    previewGeometryJson: String? = null,
    historyGeometryJson: String? = null,
    selectedPoint: GpsPoint? = null,
    /** Draws every entry of [gpsPoints] as a tappable dot over the trace. */
    showPointDots: Boolean = false,
    /** Called with the tapped point's id, or null when the tap hit no dot. */
    onPointTap: ((Long?) -> Unit)? = null,
    followLocation: Boolean = false,
    showCurrentPosition: Boolean = false,
    initialLatLng: LatLng? = null,
    onMapReady: (MapLibreMap) -> Unit = {},
    onMapClick: ((LatLng) -> Unit)? = null,
    onCameraMove: ((LatLng) -> Unit)? = null,
) {
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapView by remember { mutableStateOf<MapView?>(null) }

    // rememberUpdatedState ensures async callbacks (getMapAsync/setStyle) always
    // see the latest parameter values, regardless of when they fire.
    val latestGpsPoints = rememberUpdatedState(gpsPoints)
    val latestRouteJson = rememberUpdatedState(routeGeometryJson)
    val latestPreviewJson = rememberUpdatedState(previewGeometryJson)
    val latestHistoryJson = rememberUpdatedState(historyGeometryJson)
    val latestSelectedPoint = rememberUpdatedState(selectedPoint)
    val latestShowPointDots = rememberUpdatedState(showPointDots)
    val latestOnPointTap = rememberUpdatedState(onPointTap)
    val latestFollowLocation = rememberUpdatedState(followLocation)
    val latestOnCameraMove = rememberUpdatedState(onCameraMove)
    val tapRadiusPx = with(LocalDensity.current) { TAP_RADIUS.toPx() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> mapView?.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                    Lifecycle.Event.ON_STOP -> mapView?.onStop()
                    Lifecycle.Event.ON_DESTROY -> mapView?.onDestroy()
                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView?.onDestroy()
        }
    }

    AndroidView(
        factory = { context ->
            MapLibre.getInstance(context)
            MapView(context).also { mapView = it }.apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                contentDescription = "Map showing walk route"
                addOnDidFailLoadingMapListener { error ->
                    Timber.e("Map style failed to load: $error (url=$styleUrl)")
                }
                getMapAsync { map ->
                    @Suppress("UNUSED_VALUE")
                    mapLibreMap = map
                    map.uiSettings.isRotateGesturesEnabled = false
                    val styleBuilder =
                        if (styleUrl.trimStart().startsWith("{")) {
                            Style.Builder().fromJson(styleUrl)
                        } else {
                            Style.Builder().fromUri(styleUrl)
                        }
                    map.setStyle(styleBuilder) { style ->
                        setupRouteLayers(style)
                        // Apply whatever geometry is already loaded — handles the common
                        // case where the DB finishes before the map style does.
                        updateRouteLayer(map, latestGpsPoints.value)
                        updateRouteJsonLayer(map, latestRouteJson.value)
                        updatePreviewLayer(map, latestPreviewJson.value)
                        updateHistoryLayer(map, latestHistoryJson.value)
                        updatePointDotsLayer(map, latestGpsPoints.value, latestShowPointDots.value)
                        updateSelectedPointLayer(map, latestSelectedPoint.value)
                        // Center on initial position when no route is loaded yet.
                        if (initialLatLng != null && latestGpsPoints.value.isEmpty()) {
                            map.moveCamera(
                                org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                                    CameraPosition.Builder()
                                        .target(initialLatLng)
                                        .zoom(15.0)
                                        .build(),
                                ),
                            )
                        }
                        // Report initial center so callers can seed their state.
                        map.cameraPosition.target?.let { latestOnCameraMove.value?.invoke(it) }
                        onMapReady(map)
                    }
                    if (onMapClick != null || onPointTap != null) {
                        map.addOnMapClickListener { latLng ->
                            latestOnPointTap.value?.invoke(
                                pointIdAt(map, map.projection.toScreenLocation(latLng), tapRadiusPx),
                            )
                            onMapClick?.invoke(latLng)
                            true
                        }
                    }
                    map.addOnCameraMoveListener {
                        map.cameraPosition.target?.let { latestOnCameraMove.value?.invoke(it) }
                    }
                }
            }
        },
        modifier = modifier,
        update = { _ ->
            val map = mapLibreMap ?: return@AndroidView
            // Sources may not exist yet if style hasn't loaded; each helper returns early if so.
            updateRouteLayer(map, gpsPoints)
            updateRouteJsonLayer(map, routeGeometryJson)
            updatePreviewLayer(map, previewGeometryJson)
            updateHistoryLayer(map, historyGeometryJson)
            updatePointDotsLayer(map, gpsPoints, showPointDots)
            updateSelectedPointLayer(map, selectedPoint)
            if (showCurrentPosition) {
                updatePositionLayer(map, gpsPoints)
            }
            if (latestFollowLocation.value && gpsPoints.isNotEmpty()) {
                val last = gpsPoints.last()
                map.animateCamera(
                    org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(last.lat, last.lng))
                            .zoom(16.0)
                            .build(),
                    ),
                )
            }
        },
    )
}

private fun setupRouteLayers(style: Style) {
    try {
        style.addSource(GeoJsonSource(GPS_ROUTE_SOURCE))
        style.addLayer(
            LineLayer(GPS_ROUTE_LAYER, GPS_ROUTE_SOURCE).withProperties(
                lineColor("#3B82F6"),
                lineWidth(4f),
                lineCap("round"),
                lineJoin("round"),
            ),
        )
        // Past walks sit below the live route: subordinate colour, and never over the
        // current trace where the two overlap.
        style.addSource(GeoJsonSource(HISTORY_SOURCE))
        style.addLayerBelow(
            LineLayer(HISTORY_LAYER, HISTORY_SOURCE).withProperties(
                lineColor("#64748B"),
                lineWidth(3f),
                lineOpacity(0.5f),
                lineCap("round"),
                lineJoin("round"),
            ),
            GPS_ROUTE_LAYER,
        )
        style.addSource(GeoJsonSource(POSITION_SOURCE))
        style.addLayer(
            CircleLayer(POSITION_LAYER, POSITION_SOURCE).withProperties(
                circleColor("#3B82F6"),
                circleRadius(8f),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(2f),
            ),
        )
        style.addSource(GeoJsonSource(ROUTE_JSON_SOURCE))
        style.addLayer(
            LineLayer(ROUTE_JSON_LAYER, ROUTE_JSON_SOURCE).withProperties(
                lineColor("#3B82F6"),
                lineWidth(4f),
                lineCap("round"),
                lineJoin("round"),
            ),
        )
        style.addSource(GeoJsonSource(PREVIEW_SOURCE))
        style.addLayer(
            LineLayer(PREVIEW_LAYER, PREVIEW_SOURCE).withProperties(
                lineColor("#F59E0B"),
                lineWidth(4f),
                lineCap("round"),
                lineJoin("round"),
            ),
        )
        // One tappable dot per trace point, drawn over the trace and under the selected-point
        // marker. Fixed radius at every zoom: what the user can see is always what they can tap.
        style.addSource(GeoJsonSource(POINT_DOTS_SOURCE))
        style.addLayer(
            CircleLayer(POINT_DOTS_LAYER, POINT_DOTS_SOURCE).withProperties(
                circleColor("#1D4ED8"),
                circleRadius(5f),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(1.5f),
            ),
        )
        style.addSource(GeoJsonSource(SELECTED_POINT_SOURCE))
        style.addLayer(
            CircleLayer(SELECTED_POINT_HALO_LAYER, SELECTED_POINT_SOURCE).withProperties(
                circleColor("#EF4444"),
                circleRadius(16f),
                circleOpacity(0.25f),
            ),
        )
        style.addLayer(
            CircleLayer(SELECTED_POINT_LAYER, SELECTED_POINT_SOURCE).withProperties(
                circleColor("#EF4444"),
                circleRadius(9f),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(3f),
            ),
        )
    } catch (e: Exception) {
        Timber.e(e, "Failed to set up route layers")
    }
}

private fun updateRouteLayer(
    map: MapLibreMap,
    points: List<GpsPoint>,
) {
    if (points.size < 2) return
    val style = map.style ?: return
    val source = style.getSourceAs<GeoJsonSource>(GPS_ROUTE_SOURCE) ?: return
    source.setGeoJson(TraceGeometry.lineStringFeature(points.filter { !it.isFiltered }.map { it.toLatLng() }))
}

private fun updatePositionLayer(
    map: MapLibreMap,
    points: List<GpsPoint>,
) {
    val last = points.lastOrNull { !it.isFiltered } ?: return
    val style = map.style ?: return
    val source = style.getSourceAs<GeoJsonSource>(POSITION_SOURCE) ?: return
    val geojson = """{"type":"Feature","geometry":{"type":"Point","coordinates":[${last.lng},${last.lat}]},"properties":{}}"""
    source.setGeoJson(geojson)
}

private fun updateRouteJsonLayer(
    map: MapLibreMap,
    geojson: String?,
) {
    val style = map.style ?: return
    val source = style.getSourceAs<GeoJsonSource>(ROUTE_JSON_SOURCE) ?: return
    if (geojson != null) {
        source.setGeoJson(geojson)
    }
}

private fun updatePreviewLayer(
    map: MapLibreMap,
    geojson: String?,
) {
    val style = map.style ?: return
    val source = style.getSourceAs<GeoJsonSource>(PREVIEW_SOURCE) ?: return
    source.setGeoJson(geojson ?: TraceGeometry.EMPTY_FEATURE_COLLECTION)
}

private fun updateHistoryLayer(
    map: MapLibreMap,
    geojson: String?,
) {
    val style = map.style ?: return
    val source = style.getSourceAs<GeoJsonSource>(HISTORY_SOURCE) ?: return
    source.setGeoJson(geojson ?: TraceGeometry.EMPTY_FEATURE_COLLECTION)
}

private fun updatePointDotsLayer(
    map: MapLibreMap,
    points: List<GpsPoint>,
    enabled: Boolean,
) {
    val style = map.style ?: return
    val source = style.getSourceAs<GeoJsonSource>(POINT_DOTS_SOURCE) ?: return
    if (!enabled) {
        source.setGeoJson(TraceGeometry.EMPTY_FEATURE_COLLECTION)
        return
    }
    // Each dot carries its point id, so a tap can be answered with an id rather than a
    // coordinate the caller would have to match back to a point itself.
    val features =
        points.joinToString(",") {
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[${it.lng},${it.lat}]},""" +
                """"properties":{"$POINT_ID_PROPERTY":${it.id}}}"""
        }
    source.setGeoJson("""{"type":"FeatureCollection","features":[$features]}""")
}

/**
 * The id of the point drawn nearest [screenPoint], or null if the tap missed every dot.
 * Hit-testing asks the renderer what it actually drew in a [tapRadiusPx] box around the tap,
 * so the touch target matches the dots on screen at any zoom without bespoke distance maths.
 */
private fun pointIdAt(
    map: MapLibreMap,
    screenPoint: PointF,
    tapRadiusPx: Float,
): Long? {
    val box =
        RectF(
            screenPoint.x - tapRadiusPx,
            screenPoint.y - tapRadiusPx,
            screenPoint.x + tapRadiusPx,
            screenPoint.y + tapRadiusPx,
        )
    return map
        .queryRenderedFeatures(box, POINT_DOTS_LAYER)
        .firstOrNull()
        ?.getNumberProperty(POINT_ID_PROPERTY)
        ?.toLong()
}

private fun updateSelectedPointLayer(
    map: MapLibreMap,
    point: GpsPoint?,
) {
    val style = map.style ?: return
    val source = style.getSourceAs<GeoJsonSource>(SELECTED_POINT_SOURCE) ?: return
    if (point == null) {
        source.setGeoJson(TraceGeometry.EMPTY_FEATURE_COLLECTION)
        return
    }
    val geojson = """{"type":"Feature","geometry":{"type":"Point","coordinates":[${point.lng},${point.lat}]},"properties":{}}"""
    source.setGeoJson(geojson)
}

/**
 * Moves the camera to show all of [geojson], with [BOUNDS_PADDING_PX] of breathing room.
 *
 * One routine for every payload the app draws — a feature, a collection, a bare geometry, one
 * line or many — because [TraceGeometry] reads them all the same way. The camera stays put when
 * there is nothing to frame, and a payload we cannot read is logged rather than thrown at a
 * screen that has no answer for it.
 */
fun fitBoundsToGeometryJson(
    map: MapLibreMap,
    geojson: String,
) {
    // The whole move is guarded, not just the read: a degenerate box — one point, or a payload
    // that is all the same coordinate — is a camera the renderer may refuse, and a screen that
    // cannot frame its route should still draw it.
    try {
        val bounds = TraceGeometry.bounds(geojson) ?: return
        map.animateCamera(
            org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(
                LatLngBounds.Builder()
                    .include(LatLng(bounds.south, bounds.west))
                    .include(LatLng(bounds.north, bounds.east))
                    .build(),
                BOUNDS_PADDING_PX,
            ),
        )
    } catch (e: Exception) {
        Timber.e(e, "Failed to fit bounds to geometry JSON")
    }
}
