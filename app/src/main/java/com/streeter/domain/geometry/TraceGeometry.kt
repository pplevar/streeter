package com.streeter.domain.geometry

import com.streeter.domain.model.GpsPoint
import com.streeter.domain.model.LatLng
import com.streeter.domain.model.toLatLng
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A geometry payload that could not be read as GeoJSON this app produces.
 *
 * The single defined failure of [TraceGeometry]: every unreadable payload — not JSON, no
 * geometry to find, a geometry type we never write, a coordinate that is not a
 * longitude/latitude pair — arrives here, so callers do not each invent a fallback.
 */
class MalformedGeometryException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/** The smallest latitude/longitude box containing a set of coordinates. */
data class BoundingBox(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
)

/**
 * Reading, building and measuring a GPS Trace, in one place.
 *
 * Pure and Android-free — Kotlin serialization rather than `org.json`, haversine rather than
 * `Location.distanceBetween` — so every part of it runs off the main thread and under a plain
 * JVM test.
 *
 * Two behaviours are decided here rather than inherited from the per-caller copies that
 * disagreed: multi-line geometry always yields all of its lines, and distance is a spherical
 * measure rather than a difference of degrees (which is not a distance, and is skewed at every
 * latitude away from the equator).
 */
object TraceGeometry {
    /** An empty payload — a valid map source value that draws nothing. */
    const val EMPTY_FEATURE_COLLECTION = """{"type":"FeatureCollection","features":[]}"""

    private const val EARTH_RADIUS_M = 6_371_000.0

    private val json = Json { ignoreUnknownKeys = true }

    // --- Reading ---

    /**
     * The separate lines of [geometryJson], in payload order.
     *
     * Accepts the shapes the app actually stores: a `Feature` wrapping a geometry, a bare
     * geometry, a `FeatureCollection`, and `Point` / `LineString` / `MultiLineString`
     * geometries. Each feature of a collection and each line of a multi-line geometry is its
     * own run of coordinates, because the gap between two lines is not a leg anyone walked —
     * see [lengthMeters].
     *
     * An empty geometry is empty, not malformed. Anything unreadable throws
     * [MalformedGeometryException].
     */
    fun parseLines(geometryJson: String): List<List<LatLng>> {
        val root =
            try {
                json.parseToJsonElement(geometryJson)
            } catch (e: Exception) {
                throw MalformedGeometryException("Geometry payload is not JSON", e)
            }
        return linesOf(root as? JsonObject ?: malformed("Geometry payload is not a JSON object"))
    }

    /**
     * Every coordinate of [geometryJson] as one run, in payload order — for the callers that
     * draw or bound a whole payload and do not care where one line ends and the next begins.
     *
     * Measuring is not one of those callers: use [parseLines] there.
     */
    fun parse(geometryJson: String): List<LatLng> = parseLines(geometryJson).flatten()

    /** [parse], with the empty list as the one shared fallback for a malformed payload. */
    fun parseOrEmpty(geometryJson: String): List<LatLng> =
        try {
            parse(geometryJson)
        } catch (_: MalformedGeometryException) {
            emptyList()
        }

    private fun linesOf(node: JsonObject): List<List<LatLng>> =
        when (val type = node.string("type")) {
            "FeatureCollection" ->
                node.array("features").flatMap { feature ->
                    linesOf(feature as? JsonObject ?: malformed("Feature is not a JSON object"))
                }
            "Feature" -> {
                when (val geometry = node["geometry"]) {
                    null, JsonNull -> emptyList()
                    is JsonObject -> linesOf(geometry)
                    else -> malformed("Feature geometry is not a JSON object")
                }
            }
            "Point" -> listOf(listOf(latLng(node.array("coordinates"))))
            "LineString" -> listOf(node.array("coordinates").map { latLng(it) })
            "MultiLineString" -> node.array("coordinates").map { line -> asArray(line).map { latLng(it) } }
            null -> malformed("Geometry payload has no type")
            else -> malformed("Unsupported geometry type $type")
        }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: malformed("Expected an array at \"$key\"")

    private fun asArray(element: JsonElement): JsonArray = element as? JsonArray ?: malformed("Expected an array of coordinates")

    private fun latLng(element: JsonElement): LatLng = latLng(asArray(element))

    private fun latLng(pair: JsonArray): LatLng {
        if (pair.size < 2) malformed("Coordinate is not a longitude/latitude pair")
        val lng = (pair[0] as? JsonPrimitive)?.doubleOrNull ?: malformed("Coordinate longitude is not a number")
        val lat = (pair[1] as? JsonPrimitive)?.doubleOrNull ?: malformed("Coordinate latitude is not a number")
        return LatLng(lat = lat, lng = lng)
    }

    private fun malformed(message: String): Nothing = throw MalformedGeometryException(message)

    // --- Building ---

    /** [points] as a `Feature` wrapping a `LineString` — the shape the app stores and re-reads. */
    fun lineStringFeature(points: List<LatLng>): String =
        """{"type":"Feature","geometry":{"type":"LineString","coordinates":[${coordinateList(points)}]},"properties":{}}"""

    /**
     * One `LineString` feature per trace, never one line across traces: joining them would draw
     * a spurious straight segment from the end of one trace to the start of the next.
     *
     * A trace of fewer than two points is no line at all and contributes no feature.
     */
    fun featureCollection(traces: List<List<LatLng>>): String {
        val features = traces.filter { it.size >= 2 }.joinToString(",") { lineStringFeature(it) }
        return if (features.isEmpty()) EMPTY_FEATURE_COLLECTION else """{"type":"FeatureCollection","features":[$features]}"""
    }

    /**
     * The recorded traces of several walks as one `LineString` feature per walk, walks in the
     * order their points first appear.
     *
     * Outlier Points are dropped and each walk's points are ordered by time, because a GPS Trace
     * is what the recorder kept, in the order it was walked. A walk left with fewer than two
     * points contributes no feature — see [featureCollection].
     */
    fun walkHistoryFeatureCollection(points: List<GpsPoint>): String =
        featureCollection(
            points
                .filter { !it.isFiltered }
                .groupBy { it.walkId }
                .values
                .map { walkPoints -> walkPoints.sortedBy { it.timestamp }.map { it.toLatLng() } },
        )

    private fun coordinateList(points: List<LatLng>): String = points.joinToString(",") { "[${it.lng},${it.lat}]" }

    // --- Measuring ---

    /**
     * Great-circle distance between two observations, in metres.
     *
     * The same haversine measure the outlier filter records a trace with, so a distance never
     * depends on which part of the app asked for it.
     */
    fun distanceMeters(
        a: LatLng,
        b: LatLng,
    ): Double {
        val dLat = radians(b.lat - a.lat)
        val dLng = radians(b.lng - a.lng)
        val h =
            sin(dLat / 2).pow(2) +
                cos(radians(a.lat)) * cos(radians(b.lat)) *
                sin(dLng / 2).pow(2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Length of a trace along its legs, in metres. Fewer than two points is no length. */
    fun lengthMeters(points: List<LatLng>): Double = points.zipWithNext { a, b -> distanceMeters(a, b) }.sum()

    /**
     * Length of everything [geometryJson] holds, in metres.
     *
     * Each line is measured on its own, so the gap between two lines of a multi-line geometry —
     * or between two features of a collection — is never counted as a leg.
     */
    fun lengthMeters(geometryJson: String): Double = parseLines(geometryJson).sumOf { lengthMeters(it) }

    private fun radians(degrees: Double): Double = degrees / 180.0 * PI

    // --- Bounds ---

    /** Bounding box of [geometryJson]; null when it holds no coordinates. */
    fun bounds(geometryJson: String): BoundingBox? = boundsOf(parse(geometryJson))

    /** Bounding box of [points]; null when there are none. */
    fun boundsOf(points: List<LatLng>): BoundingBox? {
        if (points.isEmpty()) return null
        return BoundingBox(
            south = points.minOf { it.lat },
            west = points.minOf { it.lng },
            north = points.maxOf { it.lat },
            east = points.maxOf { it.lng },
        )
    }
}
