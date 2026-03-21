package com.streeter.data.engine

import android.content.Context
import com.graphhopper.GHRequest
import com.graphhopper.GHResponse
import com.graphhopper.GraphHopper
import com.graphhopper.config.CHProfile
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
                        Timber.i("Copying OSM PBF from assets…")
                        copyAssetToFile("osm/city.osm.pbf", osmFile)
                    }

                    val gh = GraphHopper().apply {
                        setOSMFile(osmFile.absolutePath)
                        graphHopperLocation = graphDir.absolutePath
                        setProfiles(Profile("foot").setWeighting("fastest"))
                        chPreparationHandler.setCHProfiles(CHProfile("foot"))
                        importOrLoad()
                    }

                    hopper = gh
                    mapMatching = MapMatching.fromGraphHopper(gh, PMap())
                    initialized = true
                    Timber.i("GraphHopper initialized successfully")
                } catch (e: Exception) {
                    Timber.e(e, "GraphHopper initialization failed")
                }
            }
        }
    }

    override suspend fun matchTrace(points: List<GpsPoint>): Result<MatchResult> {
        if (!initialized) return Result.failure(IllegalStateException("Engine not ready"))
        if (points.size < 2) return Result.failure(IllegalArgumentException("Need at least 2 points"))

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

    private fun buildMatchedLineString(result: com.graphhopper.matching.MatchResult): String {
        val coords = result.mergedPath.calcPoints().map { "[${it.lon},${it.lat}]" }.joinToString(",")
        return """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]},"properties":{}}"""
    }

    private fun buildRouteLineString(points: com.graphhopper.util.PointList): String {
        val coords = points.map { "[${it.lon},${it.lat}]" }.joinToString(",")
        return """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]},"properties":{}}"""
    }

    private fun copyAssetToFile(assetPath: String, dest: File) {
        dest.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }
}
