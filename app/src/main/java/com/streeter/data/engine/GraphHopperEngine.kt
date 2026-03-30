package com.streeter.data.engine

import android.content.Context
import com.graphhopper.GHRequest
import com.graphhopper.GHResponse
import com.graphhopper.GraphHopper
import com.graphhopper.util.PMap
import com.graphhopper.config.Profile
import com.graphhopper.matching.MapMatching
import com.graphhopper.matching.Observation
import com.streeter.domain.engine.RoutingEngine
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.model.LatLng
import com.streeter.domain.model.MatchResult
import com.streeter.domain.model.RouteResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GraphHopperEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : RoutingEngine {

    private val mutex = Mutex()
    private var hopper: GraphHopper? = null
    private var mapMatching: MapMatching? = null
    private var initialized = false
    private var graphBounds: com.graphhopper.util.shapes.BBox? = null

    companion object {
        private const val OSM_ASSET_PATH = "osm/city.osm.pbf"
    }

    override suspend fun isReady(): Boolean = initialized

    override suspend fun initialize() {
        mutex.withLock {
            if (initialized) return
            withContext(Dispatchers.IO) {
                try {
                    val graphDir = File(context.filesDir, "graphhopper")
                    val osmFile = File(context.filesDir, "city.osm.pbf")

                    // Copy PBF from assets on first run
                    if (!osmFile.exists()) {
                        if (!assetExists()) {
                            throw java.io.FileNotFoundException(
                                "osm/city.osm.pbf not bundled — map matching unavailable"
                            )
                        }
                        Timber.i("Copying OSM PBF from assets…")
                        copyAssetToFile(osmFile)
                    }

                    // If the PBF is newer than the cached graph, delete the cache to force reimport.
                    // This handles the case where the bundled PBF asset is replaced with a corrected one.
                    if (graphDir.exists() && osmFile.lastModified() > graphDir.lastModified()) {
                        Timber.i("OSM PBF is newer than cached graph — deleting cache to force reimport")
                        graphDir.deleteRecursively()
                    }

                    val gh = GraphHopper().apply {
                        setOSMFile(osmFile.absolutePath)
                        graphHopperLocation = graphDir.absolutePath
                        setProfiles(Profile("foot").setVehicle("foot").setWeighting("fastest"))
                        importOrLoad()
                    }

                    hopper = gh
                    graphBounds = gh.baseGraph.bounds
                    mapMatching = MapMatching.fromGraphHopper(gh, PMap().putObject("profile", "foot"))
                    initialized = true
                    Timber.i("GraphHopper initialized successfully; bounds: %s", graphBounds)
                } catch (e: java.io.FileNotFoundException) {
                    Timber.w("GraphHopper: %s", e.message)
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "GraphHopper initialization failed")
                    throw e
                }
            }
        }
    }

    private fun boundsError(lat: Double, lng: Double): Result<Nothing>? {
        val bounds = graphBounds ?: return null
        if (!bounds.contains(lat, lng)) {
            return Result.failure(
                IllegalArgumentException(
                    "Routing point ($lat, $lng) is outside the loaded map area " +
                    "(lat ${bounds.minLat}–${bounds.maxLat}, lon ${bounds.minLon}–${bounds.maxLon}). " +
                    "Ensure the bundled OSM PBF covers this location."
                )
            )
        }
        return null
    }

    override suspend fun matchTrace(points: List<GpsPoint>): Result<MatchResult> {
        if (!initialized) return Result.failure(IllegalStateException("Engine not ready"))
        if (points.size < 2) return Result.failure(IllegalArgumentException("Need at least 2 points"))

        boundsError(points.first().lat, points.first().lng)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val observations = points.map { pt ->
                    Observation(com.graphhopper.util.shapes.GHPoint(pt.lat, pt.lng))
                }
                val matchResult = mapMatching!!.match(observations)
                val wayIds = matchResult.mergedPath.calcEdges()
                    .map { it.edge.toLong() }  // edge IDs as proxy for way IDs
                val geometry = buildMatchedLineString(matchResult)
                Result.success(MatchResult(
                    snappedPoints = points.map { LatLng(it.lat, it.lng) },
                    matchedWayIds = wayIds,
                    routeGeometryJson = geometry
                ))
            } catch (e: Exception) {
                Timber.e(e, "Map matching failed")
                Result.failure(e)
            }
        }
    }

    override suspend fun route(from: LatLng, to: LatLng, via: List<LatLng>): Result<RouteResult> {
        if (!initialized) return Result.failure(IllegalStateException("Engine not ready"))

        boundsError(from.lat, from.lng)?.let { return it }
        boundsError(to.lat, to.lng)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val request = GHRequest().apply {
                    addPoint(com.graphhopper.util.shapes.GHPoint(from.lat, from.lng))
                    via.forEach { addPoint(com.graphhopper.util.shapes.GHPoint(it.lat, it.lng)) }
                    addPoint(com.graphhopper.util.shapes.GHPoint(to.lat, to.lng))
                    profile = "foot"
                    putHint("calc_points", true)
                    setPathDetails(listOf("edge_id"))
                }
                val response: GHResponse = hopper!!.route(request)
                if (response.hasErrors()) {
                    return@withContext Result.failure(RuntimeException(response.errors.first().message))
                }
                val best = response.best
                val geometry = buildRouteLineString(best.points)
                val wayIds = best.getPathDetails()["edge_id"]?.map { (it.value as Number).toLong() } ?: emptyList()
                Result.success(RouteResult(
                    geometryJson = geometry,
                    distanceM = best.distance,
                    wayIds = wayIds
                ))
            } catch (e: Exception) {
                Timber.e(e, "Routing failed")
                Result.failure(e)
            }
        }
    }

    override fun getStreetName(edgeId: Long): String? {
        val gh = hopper ?: return null
        return try {
            gh.baseGraph.getEdgeIteratorState(edgeId.toInt(), Integer.MIN_VALUE)
                .getName()
                .takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildMatchedLineString(result: com.graphhopper.matching.MatchResult): String {
        val coords = result.mergedPath.calcPoints().joinToString(",") { "[${it.lon},${it.lat}]" }
        return """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]},"properties":{}}"""
    }

    private fun buildRouteLineString(points: com.graphhopper.util.PointList): String {
        val coords = points.joinToString(",") { "[${it.lon},${it.lat}]" }
        return """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]},"properties":{}}"""
    }

    private fun assetExists(): Boolean =
        try {
            context.assets.open(OSM_ASSET_PATH).use { true }
        } catch (_: java.io.FileNotFoundException) {
            false
        }

    private fun copyAssetToFile(dest: File) {
        dest.parentFile?.mkdirs()
        context.assets.open(OSM_ASSET_PATH).use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}
