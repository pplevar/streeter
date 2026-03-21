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
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import timber.log.Timber

val MAP_STYLE_URL = """
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

@Composable
fun MapLibreMapView(
    modifier: Modifier = Modifier,
    styleUrl: String,
    gpsPoints: List<GpsPoint> = emptyList(),
    followLocation: Boolean = false,
    showCurrentPosition: Boolean = false,
    onMapReady: (MapLibreMap) -> Unit = {},
    onMapClick: ((LatLng) -> Unit)? = null
) {
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapView by remember { mutableStateOf<MapView?>(null) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
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
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                contentDescription = "Map showing walk route"
                addOnDidFailLoadingMapListener { error ->
                    Timber.e("Map style failed to load: $error (url=$styleUrl)")
                }
                getMapAsync { map ->
                    mapLibreMap = map
                    val styleBuilder = if (styleUrl.trimStart().startsWith("{")) {
                        Style.Builder().fromJson(styleUrl)
                    } else {
                        Style.Builder().fromUri(styleUrl)
                    }
                    map.setStyle(styleBuilder) { style ->
                        setupRouteLayers(style)
                        onMapReady(map)
                    }
                    onMapClick?.let { callback ->
                        map.addOnMapClickListener { latLng ->
                            callback(latLng)
                            true
                        }
                    }
                }
            }
        },
        modifier = modifier,
        update = { _ ->
            val map = mapLibreMap ?: return@AndroidView
            updateRouteLayer(map, gpsPoints)
            if (showCurrentPosition) {
                updatePositionLayer(map, gpsPoints)
            }
            if (followLocation && gpsPoints.isNotEmpty()) {
                val last = gpsPoints.last()
                map.animateCamera(
                    org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(last.lat, last.lng))
                            .zoom(16.0)
                            .build()
                    )
                )
            }
        }
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
                lineJoin("round")
            )
        )
        style.addSource(GeoJsonSource(POSITION_SOURCE))
        style.addLayer(
            CircleLayer(POSITION_LAYER, POSITION_SOURCE).withProperties(
                circleColor("#3B82F6"),
                circleRadius(8f),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(2f)
            )
        )
    } catch (e: Exception) {
        Timber.e(e, "Failed to set up route layers")
    }
}

private fun updateRouteLayer(map: MapLibreMap, points: List<GpsPoint>) {
    if (points.size < 2) return
    val style = map.style ?: return
    val source = style.getSourceAs<GeoJsonSource>(GPS_ROUTE_SOURCE) ?: return
    val geojson = buildLineStringGeoJson(points.filter { !it.isFiltered })
    source.setGeoJson(geojson)
}

private fun updatePositionLayer(map: MapLibreMap, points: List<GpsPoint>) {
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

fun fitBoundsToPoints(map: MapLibreMap, points: List<GpsPoint>) {
    if (points.isEmpty()) return
    val builder = LatLngBounds.Builder()
    points.forEach { builder.include(LatLng(it.lat, it.lng)) }
    val bounds = builder.build()
    map.animateCamera(
        org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(bounds, 64)
    )
}
