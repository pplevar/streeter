package com.streeter.data.engine

import android.content.Context
import com.graphhopper.GHRequest
import com.graphhopper.GHResponse
import com.graphhopper.GraphHopper
import com.graphhopper.util.PMap
import com.graphhopper.config.Profile
import com.graphhopper.config.LMProfile
import com.graphhopper.matching.MapMatching
import com.graphhopper.matching.Observation
import com.streeter.domain.engine.RoutingEngine
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.model.LatLng
import com.streeter.domain.model.MatchResult
import com.streeter.domain.model.RouteResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
        // Bump when the cached graph format changes (e.g., LM profile added) to force a rebuild.
        private const val GRAPH_CACHE_VERSION = "v2-lm"
    }

    override suspend fun isReady(): Boolean = initialized

    override suspend fun initialize() {
        mutex.withLock {
            if (initialized) return
            withContext(Dispatchers.IO) {
                try {
                    val graphDir = File(context.filesDir, "graphhopper")
                    val osmFile = File(context.filesDir, "city.osm.pbf")
                    val versionFile = File(graphDir, ".cache_version")

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

                    // If the cache was built without LM, mark it for a one-time rebuild.
                    // We write the version file BEFORE deleting, so an interrupted rebuild doesn't
                    // cause the deletion to repeat on every subsequent run.
                    if (graphDir.exists() &&
                        (!versionFile.exists() || versionFile.readText().trim() != GRAPH_CACHE_VERSION)
                    ) {
                        Timber.i("Graph cache version mismatch — rebuilding with LM")
                        versionFile.writeText(GRAPH_CACHE_VERSION)  // write first, delete second
                        graphDir.deleteRecursively()
                    }

                    val gh = GraphHopper().apply {
                        setOSMFile(osmFile.absolutePath)
                        graphHopperLocation = graphDir.absolutePath
                        setProfiles(Profile("foot").setVehicle("foot").setWeighting("fastest"))
                        // LM (Landmarks) gives 10–100× faster A* queries, which dramatically speeds
                        // up the HMM transition routing inside MapMatching.
                        lmPreparationHandler.setLMProfiles(LMProfile("foot"))
                        importOrLoad()
                    }

                    hopper = gh
                    graphBounds = gh.baseGraph.bounds
                    mapMatching = MapMatching.fromGraphHopper(gh, PMap()
                        .putObject("profile", "foot")
                        // Reduce candidate search radius from the 40 m default to 15 m.
                        // This cuts candidates-per-observation from ~15 to ~3-5, which reduces
                        // HMM transition allocations by ~10-20× and eliminates GC pressure.
                        // Modern phone GPS accuracy is 5-15 m, so 15 m radius is sufficient
                        // to snap to the correct street.
                        .putObject("gps_accuracy", 15)
                        // Cap nodes visited per HMM transition to prevent runaway searches on
                        // long GPS gaps while still finding good matches on normal city walks.
                        .putObject("max_visited_nodes", 2000)
                    )
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
                // 20 m threshold: keeps enough detail for accurate matching while halving
                // the observation count versus 10 m, cutting HMM transition work ~4×.
                val deduplicated = deduplicatePoints(points, minDistanceMeters = 20.0)
                if (deduplicated.size < 2) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Trace collapses to fewer than 2 points after deduplication")
                    )
                }
                val observations = deduplicated.map { pt ->
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

        // 0 or 1 via point — single GH call is the fast path.
        if (via.size <= 1) {
            return withContext(Dispatchers.IO) { routeSegment(from, to, via) }
        }

        // Many via points: fan out segment pairs in parallel, then merge.
        // GH routes each consecutive pair independently anyway; doing it in parallel
        // gives near-linear speedup and avoids the single-thread bottleneck.
        return withContext(Dispatchers.IO) {
            val allPoints = listOf(from) + via + listOf(to)
            val segmentResults = allPoints.zipWithNext()
                .map { (a, b) -> async { routeSegment(a, b, emptyList()) } }
                .awaitAll()

            val failure = segmentResults.firstOrNull { it.isFailure }
            if (failure != null) return@withContext failure

            mergeRouteResults(segmentResults.map { it.getOrThrow() })
        }
    }

    /**
     * Routes a single segment (from → optional via → to) and returns a [RouteResult].
     * Must be called from an IO-dispatcher context; it is synchronous and non-suspending.
     */
    private fun routeSegment(from: LatLng, to: LatLng, via: List<LatLng>): Result<RouteResult> {
        return try {
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
                return Result.failure(RuntimeException(response.errors.first().message))
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

    /**
     * Concatenates a list of per-segment [RouteResult]s into a single result.
     * Junction points (shared between consecutive segments) are de-duplicated.
     */
    private fun mergeRouteResults(results: List<RouteResult>): Result<RouteResult> {
        if (results.isEmpty()) return Result.failure(IllegalArgumentException("No segments to merge"))
        if (results.size == 1) return Result.success(results[0])

        val allCoords = mutableListOf<LatLng>()
        for ((i, result) in results.withIndex()) {
            val coords = parseCoordinates(result.geometryJson)
            if (i == 0) allCoords.addAll(coords)
            else allCoords.addAll(coords.drop(1)) // first point duplicates previous segment's last
        }

        val coordStr = allCoords.joinToString(",") { "[${it.lng},${it.lat}]" }
        val geometry = """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coordStr]},"properties":{}}"""
        return Result.success(RouteResult(
            geometryJson = geometry,
            distanceM = results.sumOf { it.distanceM },
            wayIds = results.flatMap { it.wayIds }
        ))
    }

    private fun parseCoordinates(geometryJson: String): List<LatLng> {
        return try {
            val obj = org.json.JSONObject(geometryJson)
            val arr = obj.getJSONObject("geometry").getJSONArray("coordinates")
            (0 until arr.length()).map { i ->
                val pair = arr.getJSONArray(i)
                LatLng(lat = pair.getDouble(1), lng = pair.getDouble(0))
            }
        } catch (_: Exception) {
            emptyList()
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

    /**
     * Removes consecutive GPS points that are closer than [minDistanceMeters] to the previous
     * kept point. This prevents the GraphHopper QueryGraph from creating more virtual nodes than
     * its internal array can accommodate, which causes IndexOutOfBoundsException during matching.
     */
    private fun deduplicatePoints(points: List<GpsPoint>, minDistanceMeters: Double): List<GpsPoint> {
        val result = mutableListOf(points.first())
        for (pt in points.drop(1)) {
            val prev = result.last()
            if (haversineMeters(prev.lat, prev.lng, pt.lat, pt.lng) >= minDistanceMeters) {
                result += pt
            }
        }
        // Always include the last point so the trace end is preserved.
        if (result.last() !== points.last()) result += points.last()
        return result
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dPhi / 2).let { it * it } +
                Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2).let { it * it }
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
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
