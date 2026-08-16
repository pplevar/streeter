package com.streeter.ui.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.streeter.domain.geometry.TraceGeometry
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import timber.log.Timber

private val MAP_STYLE_JSON =
    """
    {
      "version": 8,
      "sources": {
        "osm": {
          "type": "raster",
          "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
          "tileSize": 256,
          "attribution": "© OpenStreetMap contributors",
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

/** Breathing room left around a fitted route, so its ends do not sit against the screen edge. */
private const val BOUNDS_PADDING_PX = 64

/** Half-width of the hit-test box around a tap — a fingertip's worth of slack around a dot. */
private val TAP_RADIUS = 20.dp

/** Zoom the camera settles at when it is following a live walk. */
private const val FOLLOW_ZOOM = 16.0

/** Zoom for a map opened at a place rather than at a route. */
private const val INITIAL_ZOOM = 15.0

/**
 * The map, drawing whatever [layers] a screen declares.
 *
 * A screen names the layers it wants — see [MapLayer] — and leaves out the rest; what those
 * names mean for the renderer is decided by [mapPlanOf], which a JVM test can read. This view
 * keeps only what needs a live map: the style, the view's lifecycle, hit-testing and the camera.
 *
 * @param fitBoundsTo geometry the camera should frame once, whenever it changes.
 * @param followLocation keeps the camera on the head of the trace as a walk is recorded.
 * @param initialLatLng where to open when there is no geometry to frame yet.
 */
@Suppress("DEPRECATION") // LocalLifecycleOwner: lifecycle-runtime-compose not yet in deps
@Composable
fun MapLibreMapView(
    modifier: Modifier = Modifier,
    layers: List<MapLayer> = emptyList(),
    fitBoundsTo: String? = null,
    followLocation: Boolean = false,
    initialLatLng: LatLng? = null,
    onMapReady: (MapLibreMap) -> Unit = {},
    onMapClick: ((LatLng) -> Unit)? = null,
    onCameraMove: ((LatLng) -> Unit)? = null,
) {
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    val plan = mapPlanOf(layers)
    // What each source is already holding, so a recomposition that changed one layer does not
    // hand the renderer all eight payloads again.
    val applied = remember { mutableMapOf<MapSlot, String>() }

    // Every value the map's async callbacks read is tracked the same way, so a callback that
    // arrives after the first composition is the one that gets invoked.
    val latestPlan = rememberUpdatedState(plan)
    val latestFollowLocation = rememberUpdatedState(followLocation)
    val latestOnMapReady = rememberUpdatedState(onMapReady)
    val latestOnMapClick = rememberUpdatedState(onMapClick)
    val latestOnCameraMove = rememberUpdatedState(onCameraMove)
    val tapRadiusPx = with(LocalDensity.current) { TAP_RADIUS.toPx() }

    // The renderer's own state, kept across configuration changes and process death so a
    // returning map opens where the user left it.
    val mapState = rememberSaveable { Bundle() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                val view = mapView ?: return@LifecycleEventObserver
                when (event) {
                    Lifecycle.Event.ON_START -> view.onStart()
                    Lifecycle.Event.ON_RESUME -> view.onResume()
                    Lifecycle.Event.ON_PAUSE -> view.onPause()
                    // Stopping is the last moment the renderer's state is still there to save.
                    Lifecycle.Event.ON_STOP -> {
                        view.onSaveInstanceState(mapState)
                        view.onStop()
                    }
                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        // Destroying is left to onDispose alone: leaving the composition destroys the view
        // exactly once, whether the owner is being destroyed or the map is simply going away.
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView?.onDestroy()
            mapView = null
        }
    }

    val context = LocalContext.current
    DisposableEffect(context) {
        val callbacks =
            object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {}

                override fun onLowMemory() {
                    mapView?.onLowMemory()
                }

                override fun onConfigurationChanged(newConfig: Configuration) {}
            }
        context.registerComponentCallbacks(callbacks)
        onDispose { context.unregisterComponentCallbacks(callbacks) }
    }

    AndroidView(
        factory = { ctx ->
            MapLibre.getInstance(ctx)
            MapView(ctx).also { mapView = it }.apply {
                // Empty on a first run, and the renderer treats an unmarked bundle as one.
                onCreate(mapState)
                // A map composed into an owner that is already running gets no further ON_START
                // or ON_RESUME to catch, so it is walked up to where the owner already is.
                lifecycleOwner.lifecycle.currentState.let { state ->
                    if (state.isAtLeast(Lifecycle.State.STARTED)) onStart()
                    if (state.isAtLeast(Lifecycle.State.RESUMED)) onResume()
                }
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                contentDescription = "Map showing walk route"
                addOnDidFailLoadingMapListener { error -> Timber.e("Map style failed to load: $error") }
                getMapAsync { map ->
                    @Suppress("UNUSED_VALUE")
                    mapLibreMap = map
                    map.uiSettings.isRotateGesturesEnabled = false
                    map.setStyle(Style.Builder().fromJson(MAP_STYLE_JSON)) { style ->
                        addSlotLayers(style)
                        // Apply whatever the screen already declared — handles the common case
                        // where the DB finishes before the map style does.
                        applyPlan(map, latestPlan.value, applied)
                        // Center on the initial position when the screen has no walk to frame.
                        if (initialLatLng != null && latestPlan.value.hasNoWalkYet) {
                            map.moveCamera(
                                CameraUpdateFactory.newCameraPosition(
                                    CameraPosition.Builder().target(initialLatLng).zoom(INITIAL_ZOOM).build(),
                                ),
                            )
                        }
                        // Report the initial center so callers can seed their state.
                        map.cameraPosition.target?.let { latestOnCameraMove.value?.invoke(it) }
                        latestOnMapReady.value(map)
                    }
                    // Registered unconditionally so a screen that gains a handler after the
                    // first composition is heard; a tap is only consumed if someone wanted it.
                    map.addOnMapClickListener { latLng ->
                        val onPointTap = latestPlan.value.onPointTap
                        val onClick = latestOnMapClick.value
                        onPointTap?.invoke(pointIdAt(map, map.projection.toScreenLocation(latLng), tapRadiusPx))
                        onClick?.invoke(latLng)
                        onPointTap != null || onClick != null
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
            // Sources may not exist yet if the style hasn't loaded; applyPlan returns early if so.
            applyPlan(map, plan, applied)
            if (latestFollowLocation.value) {
                plan.followTarget?.let { head ->
                    map.animateCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder().target(LatLng(head.lat, head.lng)).zoom(FOLLOW_ZOOM).build(),
                        ),
                    )
                }
            }
        },
    )

    // The camera frames a route once per route, in one place rather than in an identical
    // effect on every screen that shows one.
    LaunchedEffect(mapLibreMap, fitBoundsTo) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val geoJson = fitBoundsTo ?: return@LaunchedEffect
        fitBoundsToGeometryJson(map, geoJson)
    }
}

/**
 * Adds one source and its layers per [MapSlot], in declaration order.
 *
 * Order is the enum's, so what covers what is settled by the slot list rather than by the order
 * calls happen to be written here.
 */
private fun addSlotLayers(style: Style) {
    try {
        for (slot in MapSlot.entries) {
            style.addSource(GeoJsonSource(slot.sourceId))
            layersFor(slot).forEach { style.addLayer(it) }
        }
    } catch (e: Exception) {
        Timber.e(e, "Failed to set up map layers")
    }
}

private fun layersFor(slot: MapSlot): List<Layer> =
    when (slot) {
        // Past walks are context: a subordinate colour, and translucent where they overlap.
        MapSlot.HISTORY ->
            listOf(
                LineLayer(slot.layerId, slot.sourceId).withProperties(
                    lineColor("#64748B"),
                    lineWidth(3f),
                    lineOpacity(0.5f),
                    lineCap("round"),
                    lineJoin("round"),
                ),
            )
        MapSlot.TRACE, MapSlot.ROUTE ->
            listOf(
                LineLayer(slot.layerId, slot.sourceId).withProperties(
                    lineColor("#3B82F6"),
                    lineWidth(4f),
                    lineCap("round"),
                    lineJoin("round"),
                ),
            )
        // A highlight and an uncommitted edit share a colour but not a layer: each screen can
        // change its own without silently changing the other's.
        MapSlot.HIGHLIGHTED_WALK, MapSlot.ROUTE_PREVIEW ->
            listOf(
                LineLayer(slot.layerId, slot.sourceId).withProperties(
                    lineColor("#F59E0B"),
                    lineWidth(4f),
                    lineCap("round"),
                    lineJoin("round"),
                ),
            )
        MapSlot.CURRENT_POSITION ->
            listOf(
                CircleLayer(slot.layerId, slot.sourceId).withProperties(
                    circleColor("#3B82F6"),
                    circleRadius(8f),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(2f),
                ),
            )
        // Fixed radius at every zoom: what the user can see is always what they can tap.
        MapSlot.TRACE_POINTS ->
            listOf(
                CircleLayer(slot.layerId, slot.sourceId).withProperties(
                    circleColor("#1D4ED8"),
                    circleRadius(5f),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(1.5f),
                ),
            )
        MapSlot.SELECTED_POINT ->
            listOf(
                CircleLayer("${slot.layerId}_halo", slot.sourceId).withProperties(
                    circleColor("#EF4444"),
                    circleRadius(16f),
                    circleOpacity(0.25f),
                ),
                CircleLayer(slot.layerId, slot.sourceId).withProperties(
                    circleColor("#EF4444"),
                    circleRadius(9f),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(3f),
                ),
            )
    }

private val MapSlot.layerId: String get() = "${name.lowercase()}_layer"

/**
 * Hands each slot's source the payload [plan] decided on, skipping the slots already holding it
 * — [applied] is what the renderer was last given. No style yet means nothing to update.
 */
private fun applyPlan(
    map: MapLibreMap,
    plan: MapPlan,
    applied: MutableMap<MapSlot, String>,
) {
    val style = map.style ?: return
    for (slot in MapSlot.entries) {
        val payload = plan.payloadFor(slot)
        if (applied[slot] == payload) continue
        val source = style.getSourceAs<GeoJsonSource>(slot.sourceId) ?: continue
        source.setGeoJson(payload)
        applied[slot] = payload
    }
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
        .queryRenderedFeatures(box, MapSlot.TRACE_POINTS.layerId)
        .firstOrNull()
        ?.getNumberProperty(POINT_ID_PROPERTY)
        ?.toLong()
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
            CameraUpdateFactory.newLatLngBounds(
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
