package com.streeter

import com.streeter.domain.model.EditOperation
import com.streeter.data.engine.StreetCoverageEngine
import com.streeter.domain.model.StreetSection
import org.junit.Assert.*
import org.junit.Test

/**
 * Phase 8 QA: Unit tests for EditOperation and StreetCoverageEngine logic.
 */
class RouteEditOperationTest {

    // --- EditOperation serialization / field contracts ---

    @Test
    fun `EditOperation preserves all fields round-trip`() {
        val op = EditOperation(
            id = 1L,
            walkId = 42L,
            operationOrder = 0,
            anchor1Lat = 51.5074,
            anchor1Lng = -0.1278,
            anchor2Lat = 51.5080,
            anchor2Lng = -0.1270,
            waypointLat = 51.5077,
            waypointLng = -0.1274,
            replacedGeometryJson = """{"type":"Feature","geometry":{"type":"LineString","coordinates":[]}}""",
            newGeometryJson = """{"type":"Feature","geometry":{"type":"LineString","coordinates":[]}}""",
            createdAt = 1_700_000_000_000L
        )

        assertEquals(42L, op.walkId)
        assertEquals(0, op.operationOrder)
        assertEquals(51.5074, op.anchor1Lat, 1e-6)
        assertEquals(-0.1278, op.anchor1Lng, 1e-6)
        assertNotNull(op.replacedGeometryJson)
        assertNotNull(op.newGeometryJson)
    }

    @Test
    fun `EditOperation replaced and new geometry are distinct`() {
        val replaced = """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[0.0,0.0],[1.0,1.0]]}}"""
        val updated = """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[0.0,0.0],[0.5,1.5],[1.0,1.0]]}}"""

        val op = EditOperation(
            walkId = 1L,
            operationOrder = 0,
            anchor1Lat = 0.0, anchor1Lng = 0.0,
            anchor2Lat = 1.0, anchor2Lng = 1.0,
            waypointLat = 0.5, waypointLng = 1.5,
            replacedGeometryJson = replaced,
            newGeometryJson = updated,
            createdAt = System.currentTimeMillis()
        )

        assertNotEquals(op.replacedGeometryJson, op.newGeometryJson)
        assertTrue(op.replacedGeometryJson.contains("0.0,0.0"))
        assertTrue(op.newGeometryJson.contains("0.5,1.5"))
    }

    // --- StreetCoverageEngine: stable ID generation ---

    private val engine = StreetCoverageEngine(
        streetRepository = FakeStreetRepository(),
        routingEngine = FakeRoutingEngine()
    )

    @Test
    fun `stable ID is consistent for same inputs`() {
        val id1 = engine.generateStableId("Main Street", 100L, 101L)
        val id2 = engine.generateStableId("Main Street", 100L, 101L)
        assertEquals(id1, id2)
    }

    @Test
    fun `stable ID differs when street name changes`() {
        val id1 = engine.generateStableId("Main Street", 100L, 101L)
        val id2 = engine.generateStableId("Oak Avenue", 100L, 101L)
        assertNotEquals(id1, id2)
    }

    @Test
    fun `stable ID is 16 hex characters`() {
        val id = engine.generateStableId("Elm Road", 200L, 201L)
        assertEquals(16, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // --- StreetCoverageEngine: streetCoverage rollup ---

    @Test
    fun `single fully covered section yields 1_0`() {
        val section = makeSection("s1", 100.0)
        val result = engine.streetCoverage(listOf(section), mapOf("s1" to 1.0f))
        assertEquals(1.0f, result, 0.001f)
    }

    @Test
    fun `single uncovered section yields 0_0`() {
        val section = makeSection("s1", 100.0)
        val result = engine.streetCoverage(listOf(section), mapOf())
        assertEquals(0.0f, result, 0.001f)
    }

    @Test
    fun `length-weighted average two equal sections half covered`() {
        val s1 = makeSection("s1", 100.0)
        val s2 = makeSection("s2", 100.0)
        val result = engine.streetCoverage(listOf(s1, s2), mapOf("s1" to 1.0f))
        assertEquals(0.5f, result, 0.001f)
    }

    @Test
    fun `length-weighted average unequal sections`() {
        val long = makeSection("s1", 300.0)
        val short = makeSection("s2", 100.0)
        // long section fully covered, short not → 300/(300+100) = 0.75
        val result = engine.streetCoverage(listOf(long, short), mapOf("s1" to 1.0f))
        assertEquals(0.75f, result, 0.001f)
    }

    @Test
    fun `empty sections list returns 0`() {
        val result = engine.streetCoverage(emptyList(), emptyMap())
        assertEquals(0f, result, 0.001f)
    }

    private fun makeSection(stableId: String, lengthM: Double) = StreetSection(
        id = 0L,
        streetId = 1L,
        fromNodeOsmId = 0L,
        toNodeOsmId = 1L,
        lengthM = lengthM,
        geometryJson = "",
        stableId = stableId,
        isOrphaned = false
    )
}

// Minimal fakes for constructor injection in unit tests
private class FakeStreetRepository : com.streeter.domain.repository.StreetRepository {
    override suspend fun upsertStreet(street: com.streeter.domain.model.Street) = 0L
    override suspend fun getSectionsByStreetId(streetId: Long) = emptyList<com.streeter.domain.model.StreetSection>()
    override suspend fun upsertSection(section: com.streeter.domain.model.StreetSection) {}
    override suspend fun getSectionByStableId(stableId: String) = null
    override suspend fun insertWalkStreetCoverage(coverage: com.streeter.domain.model.WalkStreetCoverage) {}
    override suspend fun insertWalkSectionCoverage(coverage: com.streeter.domain.model.WalkSectionCoverage) {}
    override suspend fun deleteWalkCoverageForWalk(walkId: Long) {}
    override suspend fun getStreetCoverageForWalk(walkId: Long) = emptyList<com.streeter.domain.model.WalkStreetCoverage>()
}

private class FakeRoutingEngine : com.streeter.domain.engine.RoutingEngine {
    override suspend fun isReady() = true
    override suspend fun initialize() {}
    override suspend fun matchTrace(points: List<com.streeter.domain.model.GpsPoint>) =
        Result.failure<com.streeter.domain.model.MatchResult>(UnsupportedOperationException("fake"))
    override suspend fun route(
        from: com.streeter.domain.model.LatLng,
        to: com.streeter.domain.model.LatLng,
        via: List<com.streeter.domain.model.LatLng>
    ) = Result.failure<com.streeter.domain.model.RouteResult>(UnsupportedOperationException("fake"))
    override fun getStreetName(edgeId: Long): String? = null
}
