package com.streeter

import com.streeter.data.engine.TransactionRunner
import com.streeter.domain.engine.RoutingEngine
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.model.LatLng
import com.streeter.domain.model.MatchResult
import com.streeter.domain.model.RouteResult
import com.streeter.domain.model.Street
import com.streeter.domain.model.StreetSection
import com.streeter.domain.model.StreetWalkEntry
import com.streeter.domain.model.WalkSectionCoverage
import com.streeter.domain.model.WalkStreetCoverage
import com.streeter.domain.repository.StreetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// Test fixtures shared across unit tests.
//
// Keep these minimal — production code should be exercised through the real interfaces.
// Each fake records its inputs where the test needs to inspect them.

/** Runs the supplied block inline — sufficient for unit tests that don't need real Room transactions. */
internal val InlineTransactionRunner = TransactionRunner { block -> block() }

/**
 * In-memory [StreetRepository] suitable for engine unit tests.
 * Captures all upserts/inserts so tests can assert on the actual values written.
 */
internal class RecordingStreetRepository : StreetRepository {
    val upsertedStreets = mutableListOf<Street>()
    val upsertedSections = mutableListOf<StreetSection>()
    val insertedWalkStreetCoverages = mutableListOf<WalkStreetCoverage>()
    val insertedWalkSectionCoverages = mutableListOf<WalkSectionCoverage>()
    val deletedWalkCoverages = mutableListOf<Long>()

    /** Stable-ids the test pretends already exist in the DB (returned from [getSectionsByStableIds]). */
    var existingStableIds: Set<String> = emptySet()

    private var streetIdSeq = 0L

    override suspend fun upsertStreet(street: Street): Long {
        upsertedStreets += street
        return ++streetIdSeq
    }

    override suspend fun upsertStreets(streets: List<Street>): List<Long> {
        upsertedStreets += streets
        return streets.map { ++streetIdSeq }
    }

    override suspend fun getSectionsByStreetId(streetId: Long): List<StreetSection> = emptyList()

    override suspend fun upsertSection(section: StreetSection) {
        upsertedSections += section
    }

    override suspend fun upsertSections(sections: List<StreetSection>) {
        upsertedSections += sections
    }

    override suspend fun getSectionByStableId(stableId: String): StreetSection? = null

    override suspend fun getSectionsByStableIds(stableIds: List<String>): List<StreetSection> =
        stableIds
            .filter { it in existingStableIds }
            .map { sid ->
                StreetSection(
                    id = 0,
                    streetId = 0,
                    fromNodeOsmId = 0,
                    toNodeOsmId = 0,
                    lengthM = 0.0,
                    geometryJson = "",
                    stableId = sid,
                    isOrphaned = false,
                )
            }

    override suspend fun insertWalkStreetCoverage(coverage: WalkStreetCoverage) {
        insertedWalkStreetCoverages += coverage
    }

    override suspend fun insertWalkStreetCoverages(coverages: List<WalkStreetCoverage>) {
        insertedWalkStreetCoverages += coverages
    }

    override suspend fun insertWalkSectionCoverage(coverage: WalkSectionCoverage) {
        insertedWalkSectionCoverages += coverage
    }

    override suspend fun insertWalkSectionCoverages(coverages: List<WalkSectionCoverage>) {
        insertedWalkSectionCoverages += coverages
    }

    override suspend fun deleteWalkCoverageForWalk(walkId: Long) {
        deletedWalkCoverages += walkId
    }

    override suspend fun getStreetCoverageForWalk(walkId: Long): List<WalkStreetCoverage> = emptyList()

    override suspend fun getStreetCountForWalk(walkId: Long): Int = 0

    override fun observeCoveredStreetCount(): Flow<Int> = flowOf(0)

    override fun observeTotalStreetCount(): Flow<Int> = flowOf(0)

    override suspend fun getStreetById(streetId: Long): Street? = null

    override suspend fun getCoveredLengthForStreet(streetId: Long): Double = 0.0

    override suspend fun getWalksForStreet(streetId: Long): List<StreetWalkEntry> = emptyList()

    override suspend fun getCoveredSectionEdgeIdsForWalk(
        walkId: Long,
        streetId: Long,
    ): List<Long> = emptyList()
}

/**
 * Programmable [RoutingEngine] — return values are looked up from per-way-id maps so tests can
 * model multi-way streets, missing names, etc. without touching GraphHopper.
 */
internal class FakeRoutingEngine(
    private val streetNamesByWay: Map<Long, String> = emptyMap(),
    private val edgeLengthsByWay: Map<Long, Double> = emptyMap(),
    private val streetTotalLengths: Map<String, Double> = emptyMap(),
) : RoutingEngine {
    override suspend fun isReady() = true

    override suspend fun initialize() = Unit

    override suspend fun matchTrace(points: List<GpsPoint>): Result<MatchResult> = Result.failure(UnsupportedOperationException("fake"))

    override suspend fun route(
        from: LatLng,
        to: LatLng,
        via: List<LatLng>,
    ): Result<RouteResult> = Result.failure(UnsupportedOperationException("fake"))

    override fun getStreetName(edgeId: Long): String? = streetNamesByWay[edgeId]

    override fun findNearestNamedStreet(edgeId: Long): String? = null

    override fun getEdgeLength(edgeId: Long): Double? = edgeLengthsByWay[edgeId]

    override fun getStreetTotalLength(streetName: String): Double? = streetTotalLengths[streetName]

    override fun getEdgeGeometry(edgeId: Long): String? = null

    override fun getEdgeGeometriesForStreet(streetName: String): List<String> = emptyList()
}
