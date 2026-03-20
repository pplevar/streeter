package com.streeter.ui.map

import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.streeter.domain.model.GpsPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import timber.log.Timber

private const val GPS_ROUTE_SOURCE = "gps_route_source"
private const val GPS_ROUTE_LAYER = "gps_route_layer"

@Composable
fun MapLibreMapView(
    modifier: Modifier = Modifier,
    styleUrl: String,
    gpsPoints: List<GpsPoint> = emptyList(),
    followLocation: Boolean = false,
    onMapReady: (MapLibreMap) -> Unit = {}
) {
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }

    AndroidView(
        factory = { context ->
            MapLibre.getInstance(context)
            MapView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                contentDescription = "Map showing walk route"
                getMapAsync { map ->
                    mapLibreMap = map
                    map.setStyle(styleUrl) { style ->
                        setupRouteLayers(style)
                        onMapReady(map)
                    }
                }
            }
        },
        modifier = modifier,
        update = { _ ->
            val map = mapLibreMap ?: return@AndroidView
            updateRouteLayer(map, gpsPoints)
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
