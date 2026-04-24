package com.streeter.ui.map

import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.streeter.domain.model.GpsPoint
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
private const val POSITION_SOURCE = "position_source"
private const val POSITION_LAYER = "position_layer"
private const val ROUTE_JSON_SOURCE = "route_json_source"
private const val ROUTE_JSON_LAYER = "route_json_layer"
private const val PREVIEW_SOURCE = "preview_source"
private const val PREVIEW_LAYER = "preview_layer"

@Suppress("DEPRECATION") // LocalLifecycleOwner: lifecycle-runtime-compose not yet in deps
@Composable
fun MapLibreMapView(
    modifier: Modifier = Modifier,
    styleUrl: String,
    gpsPoints: List<GpsPoint> = emptyList(),
    routeGeometryJson: String? = null,
    previewGeometryJson: String? = null,
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
    val latestFollowLocation = rememberUpdatedState(followLocation)
    val latestOnCameraMove = rememberUpdatedState(onCameraMove)

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
                    onMapClick?.let { callback ->
                        map.addOnMapClickListener { latLng ->
                            callback(latLng)
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
    val geojson = buildLineStringGeoJson(points.filter { !it.isFiltered })
    source.setGeoJson(geojson)
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

fun buildLineStringGeoJson(points: List<GpsPoint>): String {
    val coords = points.joinToString(",") { "[${it.lng},${it.lat}]" }
    return """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]},"properties":{}}"""
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
    source.setGeoJson(
        geojson ?: """{"type":"FeatureCollection","features":[]}""",
    )
}

fun fitBoundsToGeometryJson(
    map: MapLibreMap,
    geojson: String,
) {
    try {
        val feature = org.json.JSONObject(geojson)
        val geometry = feature.optJSONObject("geometry") ?: return
        val coordinates = geometry.optJSONArray("coordinates") ?: return
        if (coordinates.length() == 0) return
        val builder = LatLngBounds.Builder()
        for (i in 0 until coordinates.length()) {
            val coord = coordinates.getJSONArray(i)
            builder.include(LatLng(coord.getDouble(1), coord.getDouble(0)))
        }
        map.animateCamera(
            org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(builder.build(), 64),
        )
    } catch (e: Exception) {
        Timber.e(e, "Failed to fit bounds to geometry JSON")
    }
}

fun fitBoundsToJson(
    map: MapLibreMap,
    geojson: String,
) {
    try {
        val root = org.json.JSONObject(geojson)
        val builder = LatLngBounds.Builder()
        var hasPoints = false

        fun addLineCoords(coords: org.json.JSONArray) {
            for (i in 0 until coords.length()) {
                val c = coords.getJSONArray(i)
                builder.include(LatLng(c.getDouble(1), c.getDouble(0)))
                hasPoints = true
            }
        }

        fun processGeometry(geometry: org.json.JSONObject) {
            val coordinates = geometry.optJSONArray("coordinates") ?: return
            if (coordinates.length() == 0) return
            val first = coordinates.opt(0) ?: return
            when {
                first is org.json.JSONArray && first.opt(0) is Number -> addLineCoords(coordinates)
                first is org.json.JSONArray -> {
                    for (i in 0 until coordinates.length()) {
                        addLineCoords(coordinates.getJSONArray(i))
                    }
                }
                first is Number -> {
                    builder.include(LatLng(coordinates.getDouble(1), coordinates.getDouble(0)))
                    hasPoints = true
                }
            }
        }

        when (root.optString("type")) {
            "FeatureCollection" -> {
                val features = root.optJSONArray("features") ?: return
                for (i in 0 until features.length()) {
                    val geometry = features.getJSONObject(i).optJSONObject("geometry") ?: continue
                    processGeometry(geometry)
                }
            }
            "Feature" -> {
                val geometry = root.optJSONObject("geometry") ?: return
                processGeometry(geometry)
            }
            else -> processGeometry(root)
        }

        if (hasPoints) {
            map.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(builder.build(), 64),
            )
        }
    } catch (e: Exception) {
        Timber.e(e, "Failed to fit bounds to JSON")
    }
}
