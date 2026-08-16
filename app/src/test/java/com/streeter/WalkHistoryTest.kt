package com.streeter

import com.streeter.domain.geometry.TraceGeometry
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.repository.GpsPointRepository
import com.streeter.ui.map.WalkHistoryLoader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral spec for the recording map's past-walks history layer (issue #47).
 *
 * The layer draws raw GPS Traces, not Coverage — see ADR 0006. What matters here is that
 * each past walk becomes its own line, filtered points never reach the map, and the
 * in-progress walk is excluded so it is not drawn twice.
 *
 * The builder these tests pin now lives in [TraceGeometry] (issue #59); the loader that feeds
 * it is still the seam between the repository and the map.
 */
class WalkHistoryTest {
    private fun point(
        walkId: Long,
        timestamp: Long,
        lat: Double = timestamp.toDouble(),
        lng: Double = timestamp.toDouble(),
        isFiltered: Boolean = false,
        isManual: Boolean = false,
    ) = GpsPoint(
        id = walkId * 1000 + timestamp,
        walkId = walkId,
        lat = lat,
        lng = lng,
        timestamp = timestamp,
        accuracyM = 5f,
        speedKmh = 4f,
        isFiltered = isFiltered,
        isManual = isManual,
    )

    /** Coordinate arrays of each LineString feature, in feature order. */
    private fun lineStrings(geojson: String): List<List<Pair<Double, Double>>> =
        Json.parseToJsonElement(geojson).jsonObject["features"]!!.jsonArray.map { feature ->
            feature.jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.map { coordinate ->
                val pair = coordinate.jsonArray
                pair[0].jsonPrimitive.double to pair[1].jsonPrimitive.double
            }
        }

    private fun typeOf(geojson: String): String = Json.parseToJsonElement(geojson).jsonObject["type"]!!.jsonPrimitive.content

    @Test
    fun `each walk becomes its own LineString`() {
        val json =
            TraceGeometry.walkHistoryFeatureCollection(
                listOf(
                    point(walkId = 1L, timestamp = 1L),
                    point(walkId = 1L, timestamp = 2L),
                    point(walkId = 2L, timestamp = 3L),
                    point(walkId = 2L, timestamp = 4L),
                ),
            )

        val lines = lineStrings(json)

        // Two features, not one: a single line would draw a spurious segment from the end
        // of walk 1 to the start of walk 2.
        assertEquals(2, lines.size)
        assertEquals(listOf(1.0 to 1.0, 2.0 to 2.0), lines[0])
        assertEquals(listOf(3.0 to 3.0, 4.0 to 4.0), lines[1])
    }

    @Test
    fun `interleaved walks are still grouped into one line each`() {
        val json =
            TraceGeometry.walkHistoryFeatureCollection(
                listOf(
                    point(walkId = 1L, timestamp = 1L),
                    point(walkId = 2L, timestamp = 2L),
                    point(walkId = 1L, timestamp = 3L),
                    point(walkId = 2L, timestamp = 4L),
                ),
            )

        val lines = lineStrings(json)

        assertEquals(2, lines.size)
        assertEquals(listOf(1.0 to 1.0, 3.0 to 3.0), lines[0])
        assertEquals(listOf(2.0 to 2.0, 4.0 to 4.0), lines[1])
    }

    @Test
    fun `points of one walk are ordered by timestamp`() {
        val json =
            TraceGeometry.walkHistoryFeatureCollection(
                listOf(
                    point(walkId = 1L, timestamp = 3L),
                    point(walkId = 1L, timestamp = 1L),
                    point(walkId = 1L, timestamp = 2L),
                ),
            )

        assertEquals(listOf(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0), lineStrings(json).single())
    }

    @Test
    fun `filtered points are excluded`() {
        val json =
            TraceGeometry.walkHistoryFeatureCollection(
                listOf(
                    point(walkId = 1L, timestamp = 1L),
                    point(walkId = 1L, timestamp = 2L, isFiltered = true),
                    point(walkId = 1L, timestamp = 3L),
                ),
            )

        assertEquals(listOf(1.0 to 1.0, 3.0 to 3.0), lineStrings(json).single())
    }

    @Test
    fun `a walk left with fewer than two points contributes no line`() {
        val json =
            TraceGeometry.walkHistoryFeatureCollection(
                listOf(
                    point(walkId = 1L, timestamp = 1L),
                    point(walkId = 2L, timestamp = 2L),
                    point(walkId = 2L, timestamp = 3L),
                ),
            )

        // Walk 1 has a single point: a LineString of one coordinate is invalid GeoJSON.
        assertEquals(1, lineStrings(json).size)
    }

    @Test
    fun `manually drawn walks are drawn like recorded ones`() {
        val json =
            TraceGeometry.walkHistoryFeatureCollection(
                listOf(
                    point(walkId = 5L, timestamp = 1L, isManual = true),
                    point(walkId = 5L, timestamp = 2L, isManual = true),
                ),
            )

        // Manual points are excluded from Map Matching, but history draws traces, not
        // matched routes — so a manual walk still shows up.
        assertEquals(listOf(1.0 to 1.0, 2.0 to 2.0), lineStrings(json).single())
    }

    @Test
    fun `empty history is a valid empty FeatureCollection`() {
        val json = TraceGeometry.walkHistoryFeatureCollection(emptyList())

        assertEquals("FeatureCollection", typeOf(json))
        assertTrue(lineStrings(json).isEmpty())
    }

    /** [GpsPointRepository] backed by a fixed point list; only the history query is live. */
    private class FakeGpsPointRepository(
        private val points: List<GpsPoint>,
    ) : GpsPointRepository {
        var requestedExcludeWalkId: Long? = null

        override suspend fun insertPoints(points: List<GpsPoint>) = Unit

        override suspend fun getPointsForWalk(walkId: Long): List<GpsPoint> = emptyList()

        override suspend fun getPointsForMapMatching(walkId: Long): List<GpsPoint> = emptyList()

        override fun observePointsForWalk(walkId: Long): Flow<List<GpsPoint>> = flowOf(emptyList())

        override suspend fun getPointsExcludingWalk(excludeWalkId: Long): List<GpsPoint> {
            requestedExcludeWalkId = excludeWalkId
            return points.filter { it.walkId != excludeWalkId }
        }

        override suspend fun replacePointsFromRemote(
            walkId: Long,
            points: List<GpsPoint>,
        ) = Unit

        override suspend fun deletePoint(
            walkId: Long,
            pointId: Long,
        ): Int = 0
    }

    @Test
    fun `loader excludes the in-progress walk`() =
        runTest {
            val repository =
                FakeGpsPointRepository(
                    listOf(
                        point(walkId = 1L, timestamp = 1L),
                        point(walkId = 1L, timestamp = 2L),
                        point(walkId = 7L, timestamp = 3L),
                        point(walkId = 7L, timestamp = 4L),
                    ),
                )

            val json = WalkHistoryLoader(repository).load(excludeWalkId = 7L)

            assertEquals(7L, repository.requestedExcludeWalkId)
            assertEquals(listOf(listOf(1.0 to 1.0, 2.0 to 2.0)), lineStrings(json))
        }

    @Test
    fun `loader keeps every walk when nothing is in progress`() =
        runTest {
            val repository =
                FakeGpsPointRepository(
                    listOf(
                        point(walkId = 1L, timestamp = 1L),
                        point(walkId = 1L, timestamp = 2L),
                        point(walkId = 7L, timestamp = 3L),
                        point(walkId = 7L, timestamp = 4L),
                    ),
                )

            val json = WalkHistoryLoader(repository).load(excludeWalkId = -1L)

            assertEquals(2, lineStrings(json).size)
        }

    @Test
    fun `loader on an empty database yields drawable empty geometry`() =
        runTest {
            val json = WalkHistoryLoader(FakeGpsPointRepository(emptyList())).load(excludeWalkId = -1L)

            assertTrue(lineStrings(json).isEmpty())
            assertEquals("FeatureCollection", typeOf(json))
        }
}
