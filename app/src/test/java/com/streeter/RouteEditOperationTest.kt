package com.streeter

import com.streeter.data.engine.StreetCoverageEngine
import com.streeter.domain.model.EditOperation
import com.streeter.domain.model.StreetSection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for EditOperation and StreetCoverageEngine logic.
 *
 * StreetCoverageEngine is exercised through the real class — `streetCoverage` and
 * `generateStableId` are public for testability. The end-to-end `computeAndPersistCoverage`
 * path uses recording fakes (see TestFakes.kt) so we can assert on what gets persisted.
 */
class RouteEditOperationTest {
    // ---------------------------------------------------------------------
    // EditOperation: field contract
    // ---------------------------------------------------------------------

    @Test
    fun `EditOperation preserves all fields`() {
        val op =
            EditOperation(
                id = 1L,
                walkId = 42L,
                operationOrder = 3,
                anchor1Lat = 51.5074,
                anchor1Lng = -0.1278,
                anchor2Lat = 51.5080,
                anchor2Lng = -0.1270,
                waypointLat = 51.5077,
                waypointLng = -0.1274,
                replacedGeometryJson = """{"replaced":true}""",
                newGeometryJson = """{"new":true}""",
                createdAt = 1_700_000_000_000L,
            )

        assertEquals(1L, op.id)
        assertEquals(42L, op.walkId)
        assertEquals(3, op.operationOrder)
        assertEquals(51.5074, op.anchor1Lat, 1e-9)
        assertEquals(-0.1278, op.anchor1Lng, 1e-9)
        assertEquals(51.5080, op.anchor2Lat, 1e-9)
        assertEquals(-0.1270, op.anchor2Lng, 1e-9)
        assertEquals(51.5077, op.waypointLat, 1e-9)
        assertEquals(-0.1274, op.waypointLng, 1e-9)
        assertEquals("""{"replaced":true}""", op.replacedGeometryJson)
        assertEquals("""{"new":true}""", op.newGeometryJson)
        assertEquals(1_700_000_000_000L, op.createdAt)
    }

    @Test
    fun `EditOperation replaced and new geometry are distinct`() {
        val replaced = """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[0.0,0.0],[1.0,1.0]]}}"""
        val updated = """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[0.0,0.0],[0.5,1.5],[1.0,1.0]]}}"""

        val op =
            EditOperation(
                walkId = 1L,
                operationOrder = 0,
                anchor1Lat = 0.0, anchor1Lng = 0.0,
                anchor2Lat = 1.0, anchor2Lng = 1.0,
                waypointLat = 0.5, waypointLng = 1.5,
                replacedGeometryJson = replaced,
                newGeometryJson = updated,
                createdAt = System.currentTimeMillis(),
            )

        assertNotEquals(op.replacedGeometryJson, op.newGeometryJson)
        assertTrue(op.replacedGeometryJson.contains("0.0,0.0"))
        assertTrue(op.newGeometryJson.contains("0.5,1.5"))
    }

    // ---------------------------------------------------------------------
    // StreetCoverageEngine: stable section ID
    //
    // Contract (see StreetCoverageEngine.generateStableId): MD5(name|from|to) → 16 hex chars.
    // The ID must survive OSM PK reassignments across data refreshes — i.e. it must be a pure
    // function of (streetName, fromNodeId, toNodeId), independent of mutable DB rowIds.
    // ---------------------------------------------------------------------

    private val engine =
        StreetCoverageEngine(
            streetRepository = RecordingStreetRepository(),
            routingEngine = FakeRoutingEngine(),
            transactionRunner = InlineTransactionRunner,
        )

    @Test
    fun `stable ID is deterministic across calls`() {
        val id1 = engine.generateStableId("Main Street", 100L, 101L)
        val id2 = engine.generateStableId("Main Street", 100L, 101L)
        assertEquals(id1, id2)
    }

    @Test
    fun `stable ID is exactly 16 lowercase hex chars`() {
        val id = engine.generateStableId("Elm Road", 200L, 201L)
        assertEquals(16, id.length)
        assertTrue("id '$id' contains non-hex chars", id.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `stable ID differs when street name differs`() {
        val id1 = engine.generateStableId("Main Street", 100L, 101L)
        val id2 = engine.generateStableId("Oak Avenue", 100L, 101L)
        assertNotEquals(id1, id2)
    }

    @Test
    fun `stable ID differs when fromNodeId differs`() {
        val id1 = engine.generateStableId("Main Street", 100L, 200L)
        val id2 = engine.generateStableId("Main Street", 101L, 200L)
        assertNotEquals(id1, id2)
    }

    @Test
    fun `stable ID differs when toNodeId differs`() {
        val id1 = engine.generateStableId("Main Street", 100L, 200L)
        val id2 = engine.generateStableId("Main Street", 100L, 201L)
        assertNotEquals(id1, id2)
    }

    @Test
    fun `stable ID is direction-sensitive (from-to ordering matters)`() {
        // The current implementation hashes "name|from|to", so swapping the endpoints yields
        // a different ID. This test pins that behaviour — if we ever want order-independence
        // (e.g. to dedupe undirected edges), this test will need to change AND the engine logic
        // must be updated to sort nodeIds before hashing.
        val forward = engine.generateStableId("Main Street", 100L, 101L)
        val reverse = engine.generateStableId("Main Street", 101L, 100L)
        assertNotEquals(forward, reverse)
    }

    @Test
    fun `stable ID is case-sensitive on street name`() {
        // Pin behaviour: names are not normalised before hashing. If we ever start normalising
        // OSM names (trimming, lowercasing) this test makes the change explicit.
        val a = engine.generateStableId("main street", 1L, 2L)
        val b = engine.generateStableId("Main Street", 1L, 2L)
        assertNotEquals(a, b)
    }

    @Test
    fun `stable ID matches expected MD5 prefix for known input`() {
        // Pin the exact hash so any change to the input format (separator, encoding, truncation)
        // is caught immediately. MD5("Main Street|100|101") prefix = 03df185c373912fd.
        val id = engine.generateStableId("Main Street", 100L, 101L)
        assertEquals("03df185c373912fd", id)
    }

    // ---------------------------------------------------------------------
    // StreetCoverageEngine.streetCoverage — length-weighted rollup
    // ---------------------------------------------------------------------

    @Test
    fun `streetCoverage returns 1_0 when single section is fully covered`() {
        val result = engine.streetCoverage(listOf(section("s1", 100.0)), mapOf("s1" to 1.0f))
        assertEquals(1.0f, result, 0.001f)
    }

    @Test
    fun `streetCoverage returns 0_0 when coverage map is empty`() {
        val result = engine.streetCoverage(listOf(section("s1", 100.0)), emptyMap())
        assertEquals(0.0f, result, 0.001f)
    }

    @Test
    fun `streetCoverage averages two equal-length sections`() {
        val s1 = section("s1", 100.0)
        val s2 = section("s2", 100.0)
        val result = engine.streetCoverage(listOf(s1, s2), mapOf("s1" to 1.0f))
        assertEquals(0.5f, result, 0.001f)
    }

    @Test
    fun `streetCoverage weights longer sections more heavily`() {
        // 300m fully covered + 100m uncovered → 300 / 400 = 0.75
        val long = section("s1", 300.0)
        val short = section("s2", 100.0)
        val result = engine.streetCoverage(listOf(long, short), mapOf("s1" to 1.0f))
        assertEquals(0.75f, result, 0.001f)
    }

    @Test
    fun `streetCoverage handles partial coverage values`() {
        // 200m at 50% + 200m at 25% = (100 + 50) / 400 = 0.375
        val s1 = section("s1", 200.0)
        val s2 = section("s2", 200.0)
        val result = engine.streetCoverage(listOf(s1, s2), mapOf("s1" to 0.5f, "s2" to 0.25f))
        assertEquals(0.375f, result, 0.001f)
    }

    @Test
    fun `streetCoverage treats missing entries as zero`() {
        // s2 has no entry → contributes 0
        val s1 = section("s1", 100.0)
        val s2 = section("s2", 300.0)
        val result = engine.streetCoverage(listOf(s1, s2), mapOf("s1" to 1.0f))
        assertEquals(0.25f, result, 0.001f)
    }

    @Test
    fun `streetCoverage clamps values above 1_0`() {
        // Defensive: if any caller ever passes >1.0 (e.g. floating-point drift) the rollup
        // must still be in [0, 1]. The engine's `.coerceIn(0f, 1f)` guards against this.
        val result = engine.streetCoverage(listOf(section("s1", 100.0)), mapOf("s1" to 1.5f))
        assertEquals(1.0f, result, 0.001f)
    }

    @Test
    fun `streetCoverage returns zero for empty sections`() {
        val result = engine.streetCoverage(emptyList(), emptyMap())
        assertEquals(0f, result, 0.001f)
    }

    @Test
    fun `streetCoverage returns zero when all sections have zero length`() {
        // totalLen == 0 short-circuits to 0 to avoid division by zero.
        val s1 = section("s1", 0.0)
        val s2 = section("s2", 0.0)
        val result = engine.streetCoverage(listOf(s1, s2), mapOf("s1" to 1.0f, "s2" to 1.0f))
        assertEquals(0f, result, 0.001f)
    }

    // ---------------------------------------------------------------------
    // StreetCoverageEngine.computeAndPersistCoverage — integration through the real engine
    //
    // These exercise the three-phase batched-write logic (the recent perf optimization)
    // through the public API — no internal mocking. We verify what the engine actually
    // persists when given a route, including:
    //   - Multiple OSM ways with the same name are aggregated into one WalkStreetCoverage.
    //   - Existing sections are not re-upserted (the "Phase 2 dedup" path).
    //   - Per-walk coverage is wiped first (idempotent recompute on route re-edit).
    // ---------------------------------------------------------------------

    @Test
    fun `computeAndPersistCoverage aggregates same-named ways into one street coverage row`() =
        runBlocking {
            val repo = RecordingStreetRepository()
            val routing =
                FakeRoutingEngine(
                    streetNamesByWay = mapOf(1L to "Main Street", 2L to "Main Street", 3L to "Oak Ave"),
                    edgeLengthsByWay = mapOf(1L to 100.0, 2L to 200.0, 3L to 50.0),
                    streetTotalLengths = mapOf("Main Street" to 600.0, "Oak Ave" to 200.0),
                )
            val sut = StreetCoverageEngine(repo, routing, InlineTransactionRunner)

            sut.computeAndPersistCoverage(walkId = 7L, matchedWayIds = listOf(1L, 2L, 3L))

            // Two distinct streets → exactly two WalkStreetCoverage rows
            assertEquals(2, repo.insertedWalkStreetCoverages.size)

            val main = repo.insertedWalkStreetCoverages.first { it.streetName == "Main Street" }
            assertEquals(7L, main.walkId)
            assertEquals(300.0, main.walkedLengthM, 0.001) // 100 + 200
            assertEquals(0.5f, main.coveragePct, 0.001f) // 300 / 600

            val oak = repo.insertedWalkStreetCoverages.first { it.streetName == "Oak Ave" }
            assertEquals(50.0, oak.walkedLengthM, 0.001)
            assertEquals(0.25f, oak.coveragePct, 0.001f) // 50 / 200
        }

    @Test
    fun `computeAndPersistCoverage deletes prior coverage before writing new`() =
        runBlocking {
            // Idempotent recompute: when a walk's route is edited and recomputed, the previous
            // coverage rows must be cleared first to avoid stale data.
            val repo = RecordingStreetRepository()
            val routing =
                FakeRoutingEngine(
                    streetNamesByWay = mapOf(1L to "Elm"),
                    edgeLengthsByWay = mapOf(1L to 100.0),
                    streetTotalLengths = mapOf("Elm" to 100.0),
                )
            val sut = StreetCoverageEngine(repo, routing, InlineTransactionRunner)

            sut.computeAndPersistCoverage(walkId = 9L, matchedWayIds = listOf(1L))

            assertEquals(listOf(9L), repo.deletedWalkCoverages)
        }

    @Test
    fun `computeAndPersistCoverage skips upserting sections that already exist`() =
        runBlocking {
            // Phase 2 dedup: stable IDs that already exist in the DB must NOT be re-upserted.
            // This is what makes the "MD5-based section ID stability across OSM refreshes" claim
            // actually pay off — re-running coverage doesn't churn the streetsection table.
            val repo = RecordingStreetRepository()
            val routing =
                FakeRoutingEngine(
                    streetNamesByWay = mapOf(1L to "Main", 2L to "Oak"),
                    edgeLengthsByWay = mapOf(1L to 100.0, 2L to 100.0),
                    streetTotalLengths = mapOf("Main" to 100.0, "Oak" to 100.0),
                )
            val sut = StreetCoverageEngine(repo, routing, InlineTransactionRunner)

            // Compute the stable ID for way 1 the same way the engine does, and mark it as existing.
            val mainStableId = sut.generateStableId("Main", 1L, 2L)
            repo.existingStableIds = setOf(mainStableId)

            sut.computeAndPersistCoverage(walkId = 1L, matchedWayIds = listOf(1L, 2L))

            // Only the Oak section should be inserted; Main is skipped because it already exists.
            assertEquals(1, repo.upsertedSections.size)
            assertNotEquals(mainStableId, repo.upsertedSections.single().stableId)
        }

    @Test
    fun `computeAndPersistCoverage deduplicates repeated way ids in input`() =
        runBlocking {
            // The matched-way-id list from GraphHopper can contain duplicates when the route
            // traverses the same way multiple times. The engine must collapse these via
            // `distinct()` before processing — otherwise we'd double-count walked length.
            val repo = RecordingStreetRepository()
            val routing =
                FakeRoutingEngine(
                    streetNamesByWay = mapOf(1L to "Loop St"),
                    edgeLengthsByWay = mapOf(1L to 100.0),
                    streetTotalLengths = mapOf("Loop St" to 100.0),
                )
            val sut = StreetCoverageEngine(repo, routing, InlineTransactionRunner)

            sut.computeAndPersistCoverage(walkId = 1L, matchedWayIds = listOf(1L, 1L, 1L, 1L))

            assertEquals(1, repo.insertedWalkStreetCoverages.size)
            val cov = repo.insertedWalkStreetCoverages.single()
            assertEquals(100.0, cov.walkedLengthM, 0.001) // not 400
            assertEquals(1.0f, cov.coveragePct, 0.001f)
        }

    @Test
    fun `computeAndPersistCoverage falls back to synthetic name for unnamed ways`() =
        runBlocking {
            // When neither getStreetName nor findNearestNamedStreet returns a name, the engine
            // uses "Way <id>" as a placeholder so coverage is still recorded.
            val repo = RecordingStreetRepository()
            val routing = FakeRoutingEngine() // no names configured
            val sut = StreetCoverageEngine(repo, routing, InlineTransactionRunner)

            sut.computeAndPersistCoverage(walkId = 1L, matchedWayIds = listOf(42L))

            val coverage = repo.insertedWalkStreetCoverages.single()
            assertEquals("Way 42", coverage.streetName)
            assertNotNull(repo.upsertedStreets.find { it.name == "Way 42" })
            // edgeLength fallback is 100.0 when getEdgeLength returns null
            assertEquals(100.0, coverage.walkedLengthM, 0.001)
        }

    @Test
    fun `computeAndPersistCoverage writes one WalkSectionCoverage per matched way`() =
        runBlocking {
            // Section coverage is finer-grained than street coverage: one row per distinct way.
            val repo = RecordingStreetRepository()
            val routing =
                FakeRoutingEngine(
                    streetNamesByWay = mapOf(1L to "Main", 2L to "Main", 3L to "Oak"),
                    edgeLengthsByWay = mapOf(1L to 50.0, 2L to 50.0, 3L to 50.0),
                    streetTotalLengths = mapOf("Main" to 100.0, "Oak" to 50.0),
                )
            val sut = StreetCoverageEngine(repo, routing, InlineTransactionRunner)

            sut.computeAndPersistCoverage(walkId = 1L, matchedWayIds = listOf(1L, 2L, 3L))

            assertEquals(3, repo.insertedWalkSectionCoverages.size)
            assertTrue(repo.insertedWalkSectionCoverages.all { it.coveredPct == 1.0f })
            assertTrue(repo.insertedWalkSectionCoverages.all { it.walkId == 1L })
        }

    @Test
    fun `computeAndPersistCoverage clamps coverage above 1_0 when walked length exceeds city total`() =
        runBlocking {
            // Defensive: if the routing engine reports a shorter total than the matched walked
            // length (data drift, OSM update), coverage must still be clamped to 1.0.
            val repo = RecordingStreetRepository()
            val routing =
                FakeRoutingEngine(
                    streetNamesByWay = mapOf(1L to "Main"),
                    edgeLengthsByWay = mapOf(1L to 500.0),
                    // walked length (500m) exceeds reported city total (100m)
                    streetTotalLengths = mapOf("Main" to 100.0),
                )
            val sut = StreetCoverageEngine(repo, routing, InlineTransactionRunner)

            sut.computeAndPersistCoverage(walkId = 1L, matchedWayIds = listOf(1L))

            assertEquals(1.0f, repo.insertedWalkStreetCoverages.single().coveragePct, 0.001f)
        }

    @Test
    fun `computeAndPersistCoverage emits monotonic progress callbacks`() =
        runBlocking {
            // The map-matching worker drives a heartbeat progress bar from these callbacks.
            // Verify that (processed, total) only ever advance and never exceed total.
            val repo = RecordingStreetRepository()
            val routing =
                FakeRoutingEngine(
                    streetNamesByWay = (1L..25L).associateWith { "Way $it" },
                    edgeLengthsByWay = (1L..25L).associateWith { 10.0 },
                    streetTotalLengths = (1L..25L).associate { "Way $it" to 10.0 },
                )
            val sut = StreetCoverageEngine(repo, routing, InlineTransactionRunner)

            val progress = mutableListOf<Pair<Int, Int>>()
            sut.computeAndPersistCoverage(
                walkId = 1L,
                matchedWayIds = (1L..25L).toList(),
                onProgress = { processed, total -> progress += processed to total },
            )

            assertTrue("expected at least one progress callback", progress.isNotEmpty())
            assertTrue("progress must be monotonic non-decreasing", progress.zipWithNext().all { (a, b) -> b.first >= a.first })
            assertTrue("processed must not exceed total", progress.all { it.first <= it.second })
            assertEquals(25, progress.last().first) // final callback emits the completion tick
            assertEquals(25, progress.last().second)
        }

    @Test
    fun `computeAndPersistCoverage with empty way list produces no coverage rows`() =
        runBlocking {
            val repo = RecordingStreetRepository()
            val sut = StreetCoverageEngine(repo, FakeRoutingEngine(), InlineTransactionRunner)

            sut.computeAndPersistCoverage(walkId = 1L, matchedWayIds = emptyList())

            assertTrue(repo.insertedWalkStreetCoverages.isEmpty())
            assertTrue(repo.insertedWalkSectionCoverages.isEmpty())
            assertTrue(repo.upsertedStreets.isEmpty())
            // Prior coverage is still cleared — recompute is idempotent even for empty inputs.
            assertEquals(listOf(1L), repo.deletedWalkCoverages)
        }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun section(
        stableId: String,
        lengthM: Double,
    ) = StreetSection(
        id = 0L,
        streetId = 1L,
        fromNodeOsmId = 0L,
        toNodeOsmId = 1L,
        lengthM = lengthM,
        geometryJson = "",
        stableId = stableId,
        isOrphaned = false,
    )

    // Pin the sentinel: streetCoverage must clamp negative-equivalent values too
    @Test
    fun `streetCoverage with all uncovered sections returns 0`() {
        val s1 = section("s1", 100.0)
        val s2 = section("s2", 100.0)
        val result = engine.streetCoverage(listOf(s1, s2), mapOf("s1" to 0.0f, "s2" to 0.0f))
        assertFalse(result > 0f)
        assertEquals(0f, result, 0.001f)
    }
}
