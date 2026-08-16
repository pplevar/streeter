package com.streeter

import com.streeter.domain.geometry.BoundingBox
import com.streeter.domain.geometry.MalformedGeometryException
import com.streeter.domain.geometry.TraceGeometry
import com.streeter.domain.model.LatLng
import com.streeter.service.GpsOutlierFilter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral spec for the one pure geometry module (issue #56).
 *
 * Reading, building and measuring a GPS Trace used to be re-implemented per caller against
 * Android's JSON and `Location.distanceBetween`, so none of it was reachable from a JVM test.
 * These tests pin the behaviour the copies disagreed on: multi-line geometry parses everywhere,
 * distance is spherical rather than a difference of degrees, and malformed input fails one way.
 */
class TraceGeometryTest {
    private fun feature(geometry: String) = """{"type":"Feature","geometry":$geometry,"properties":{}}"""

    private fun lineString(vararg lngLat: Pair<Double, Double>): String {
        val coords = lngLat.joinToString(",") { (lng, lat) -> "[$lng,$lat]" }
        return """{"type":"LineString","coordinates":[$coords]}"""
    }

    /** Coordinate arrays of each feature of a FeatureCollection, in feature order. */
    private fun featureCoordinates(geojson: String): List<List<Pair<Double, Double>>> =
        Json.parseToJsonElement(geojson).jsonObject["features"]!!.jsonArray.map { feature ->
            feature.jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.map { coordinate ->
                val pair = coordinate.jsonArray
                pair[0].jsonPrimitive.double to pair[1].jsonPrimitive.double
            }
        }

    // --- Parsing: the shapes actually in use ---

    @Test
    fun `parses a wrapped feature`() {
        val coords = TraceGeometry.parse(feature(lineString(1.0 to 2.0, 3.0 to 4.0)))

        assertEquals(listOf(LatLng(2.0, 1.0), LatLng(4.0, 3.0)), coords)
    }

    @Test
    fun `parses a bare geometry`() {
        val coords = TraceGeometry.parse(lineString(1.0 to 2.0, 3.0 to 4.0))

        assertEquals(listOf(LatLng(2.0, 1.0), LatLng(4.0, 3.0)), coords)
    }

    @Test
    fun `parses a feature collection into one run of coordinates in feature order`() {
        val json =
            """
            {"type":"FeatureCollection","features":[
              ${feature(lineString(1.0 to 2.0))},
              ${feature(lineString(3.0 to 4.0, 5.0 to 6.0))}
            ]}
            """.trimIndent()

        assertEquals(
            listOf(LatLng(2.0, 1.0), LatLng(4.0, 3.0), LatLng(6.0, 5.0)),
            TraceGeometry.parse(json),
        )
    }

    @Test
    fun `parses multi-line geometry by concatenating its lines in order`() {
        val json =
            """{"type":"MultiLineString","coordinates":[[[1.0,2.0],[3.0,4.0]],[[5.0,6.0]]]}"""

        assertEquals(
            listOf(LatLng(2.0, 1.0), LatLng(4.0, 3.0), LatLng(6.0, 5.0)),
            TraceGeometry.parse(json),
        )
    }

    @Test
    fun `parses multi-line geometry wrapped in a feature`() {
        val json = feature("""{"type":"MultiLineString","coordinates":[[[1.0,2.0]],[[3.0,4.0]]]}""")

        assertEquals(listOf(LatLng(2.0, 1.0), LatLng(4.0, 3.0)), TraceGeometry.parse(json))
    }

    @Test
    fun `parses a single point geometry`() {
        assertEquals(
            listOf(LatLng(2.0, 1.0)),
            TraceGeometry.parse("""{"type":"Point","coordinates":[1.0,2.0]}"""),
        )
    }

    @Test
    fun `parseLines keeps each line of a multi-line geometry apart`() {
        val json = """{"type":"MultiLineString","coordinates":[[[1.0,2.0],[3.0,4.0]],[[5.0,6.0]]]}"""

        assertEquals(
            listOf(
                listOf(LatLng(2.0, 1.0), LatLng(4.0, 3.0)),
                listOf(LatLng(6.0, 5.0)),
            ),
            TraceGeometry.parseLines(json),
        )
    }

    @Test
    fun `parseLines keeps each feature of a collection apart`() {
        val json =
            """
            {"type":"FeatureCollection","features":[
              ${feature(lineString(1.0 to 2.0))},
              ${feature(lineString(3.0 to 4.0, 5.0 to 6.0))}
            ]}
            """.trimIndent()

        assertEquals(
            listOf(
                listOf(LatLng(2.0, 1.0)),
                listOf(LatLng(4.0, 3.0), LatLng(6.0, 5.0)),
            ),
            TraceGeometry.parseLines(json),
        )
    }

    @Test
    fun `a geometry type this app never produces is malformed even where the nesting fits`() {
        assertThrows(MalformedGeometryException::class.java) {
            TraceGeometry.parse("""{"type":"MultiPoint","coordinates":[[1.0,2.0],[3.0,4.0]]}""")
        }
    }

    @Test
    fun `an empty geometry is empty, not malformed`() {
        assertEquals(emptyList<LatLng>(), TraceGeometry.parse("""{"type":"LineString","coordinates":[]}"""))
        assertEquals(emptyList<LatLng>(), TraceGeometry.parse("""{"type":"FeatureCollection","features":[]}"""))
    }

    @Test
    fun `a feature with no geometry contributes nothing`() {
        val json = """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":null,"properties":{}}]}"""

        assertEquals(emptyList<LatLng>(), TraceGeometry.parse(json))
    }

    // --- Parsing: one defined failure ---

    @Test
    fun `input that is not JSON is malformed`() {
        assertThrows(MalformedGeometryException::class.java) { TraceGeometry.parse("not json") }
    }

    @Test
    fun `an object with no geometry to read is malformed`() {
        assertThrows(MalformedGeometryException::class.java) { TraceGeometry.parse("{}") }
    }

    @Test
    fun `a geometry type this app never produces is malformed`() {
        assertThrows(MalformedGeometryException::class.java) {
            TraceGeometry.parse("""{"type":"Polygon","coordinates":[[[1.0,2.0],[3.0,4.0],[1.0,2.0]]]}""")
        }
    }

    @Test
    fun `a coordinate that is not a longitude latitude pair is malformed`() {
        assertThrows(MalformedGeometryException::class.java) {
            TraceGeometry.parse("""{"type":"LineString","coordinates":[[1.0]]}""")
        }
        assertThrows(MalformedGeometryException::class.java) {
            TraceGeometry.parse("""{"type":"LineString","coordinates":[["a","b"]]}""")
        }
    }

    @Test
    fun `parseOrEmpty is the one shared fallback for malformed input`() {
        assertEquals(emptyList<LatLng>(), TraceGeometry.parseOrEmpty("not json"))
        assertEquals(
            listOf(LatLng(2.0, 1.0)),
            TraceGeometry.parseOrEmpty(lineString(1.0 to 2.0)),
        )
    }

    // --- Building ---

    @Test
    fun `builds a wrapped line string feature that parses back to the same coordinates`() {
        val points = listOf(LatLng(2.0, 1.0), LatLng(4.0, 3.0))

        val json = TraceGeometry.lineStringFeature(points)

        assertEquals("Feature", Json.parseToJsonElement(json).jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(points, TraceGeometry.parse(json))
    }

    @Test
    fun `builds one feature per trace, never one line across traces`() {
        val json =
            TraceGeometry.featureCollection(
                listOf(
                    listOf(LatLng(2.0, 1.0), LatLng(4.0, 3.0)),
                    listOf(LatLng(6.0, 5.0), LatLng(8.0, 7.0)),
                ),
            )

        assertEquals(
            listOf(
                listOf(1.0 to 2.0, 3.0 to 4.0),
                listOf(5.0 to 6.0, 7.0 to 8.0),
            ),
            featureCoordinates(json),
        )
    }

    @Test
    fun `a trace with fewer than two points draws no line`() {
        val json = TraceGeometry.featureCollection(listOf(listOf(LatLng(2.0, 1.0)), emptyList()))

        assertEquals(emptyList<List<Pair<Double, Double>>>(), featureCoordinates(json))
        assertEquals(TraceGeometry.EMPTY_FEATURE_COLLECTION, json)
    }

    @Test
    fun `the empty feature collection is a valid source value that parses to nothing`() {
        assertEquals(emptyList<LatLng>(), TraceGeometry.parse(TraceGeometry.EMPTY_FEATURE_COLLECTION))
    }

    // --- Measuring ---

    // The outlier filter's distance is now this one — the check that the two agree has become the
    // check that the filter's helper still routes here, which is what keeps a recorded trace and a
    // drawn route measured the same way.
    @Test
    fun `the outlier filter measures distance with the shared module`() {
        val a = LatLng(52.3702, 4.8952)
        val b = LatLng(52.3760, 4.9010)

        assertEquals(
            GpsOutlierFilter.haversineMeters(a.lat, a.lng, b.lat, b.lng),
            TraceGeometry.distanceMeters(a, b),
            1e-9,
        )
    }

    @Test
    fun `a degree of longitude is shorter near the pole than at the equator`() {
        val atEquator = TraceGeometry.distanceMeters(LatLng(0.0, 0.0), LatLng(0.0, 1.0))
        val atSixty = TraceGeometry.distanceMeters(LatLng(60.0, 0.0), LatLng(60.0, 1.0))

        // cos(60 degrees) = 0.5, so the same degree span is half as long.
        assertEquals(atEquator / 2, atSixty, 1.0)
    }

    @Test
    fun `a degree of latitude is about 111 kilometres`() {
        assertEquals(111_195.0, TraceGeometry.distanceMeters(LatLng(0.0, 0.0), LatLng(1.0, 0.0)), 50.0)
    }

    @Test
    fun `trace length is the sum of its legs`() {
        val points = listOf(LatLng(52.37, 4.89), LatLng(52.38, 4.89), LatLng(52.38, 4.90))

        val expected =
            TraceGeometry.distanceMeters(points[0], points[1]) +
                TraceGeometry.distanceMeters(points[1], points[2])

        assertEquals(expected, TraceGeometry.lengthMeters(points), 1e-9)
    }

    @Test
    fun `a trace of fewer than two points has no length`() {
        assertEquals(0.0, TraceGeometry.lengthMeters(emptyList()), 0.0)
        assertEquals(0.0, TraceGeometry.lengthMeters(listOf(LatLng(52.37, 4.89))), 0.0)
    }

    @Test
    fun `the length of a multi-line geometry counts every line`() {
        val first = listOf(LatLng(52.37, 4.89), LatLng(52.38, 4.89))
        val second = listOf(LatLng(52.30, 4.95), LatLng(52.30, 4.96))
        val json =
            """{"type":"MultiLineString","coordinates":[[[4.89,52.37],[4.89,52.38]],[[4.95,52.30],[4.96,52.30]]]}"""

        assertEquals(
            TraceGeometry.lengthMeters(first) + TraceGeometry.lengthMeters(second),
            TraceGeometry.lengthMeters(json),
            1e-9,
        )
    }

    @Test
    fun `the gap between two lines is not a leg anyone walked`() {
        val json =
            """{"type":"MultiLineString","coordinates":[[[4.89,52.37],[4.89,52.38]],[[4.95,52.30],[4.96,52.30]]]}"""

        // Flattening first would charge the trace for the jump from one line to the next.
        assertTrue(TraceGeometry.lengthMeters(json) < TraceGeometry.lengthMeters(TraceGeometry.parse(json)))
    }

    @Test
    fun `the length of a feature collection counts each walk on its own`() {
        val json =
            TraceGeometry.featureCollection(
                listOf(
                    listOf(LatLng(52.37, 4.89), LatLng(52.38, 4.89)),
                    listOf(LatLng(52.30, 4.95), LatLng(52.30, 4.96)),
                ),
            )

        assertEquals(
            TraceGeometry.lengthMeters(listOf(LatLng(52.37, 4.89), LatLng(52.38, 4.89))) +
                TraceGeometry.lengthMeters(listOf(LatLng(52.30, 4.95), LatLng(52.30, 4.96))),
            TraceGeometry.lengthMeters(json),
            1e-9,
        )
    }

    // --- Bounds ---

    @Test
    fun `bounds span every coordinate of the geometry`() {
        val json =
            """{"type":"MultiLineString","coordinates":[[[4.89,52.37],[4.95,52.30]],[[4.80,52.40]]]}"""

        assertEquals(
            BoundingBox(south = 52.30, west = 4.80, north = 52.40, east = 4.95),
            TraceGeometry.bounds(json),
        )
    }

    @Test
    fun `a single point has bounds of zero extent`() {
        assertEquals(
            BoundingBox(south = 52.37, west = 4.89, north = 52.37, east = 4.89),
            TraceGeometry.bounds("""{"type":"Point","coordinates":[4.89,52.37]}"""),
        )
    }

    @Test
    fun `a geometry with no coordinates has no bounds`() {
        assertNull(TraceGeometry.bounds(TraceGeometry.EMPTY_FEATURE_COLLECTION))
        assertNull(TraceGeometry.boundsOf(emptyList()))
    }

    @Test
    fun `bounds of malformed input fails the same way parsing does`() {
        assertThrows(MalformedGeometryException::class.java) { TraceGeometry.bounds("not json") }
    }
}
