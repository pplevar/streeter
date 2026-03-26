package com.streeter.data.engine

import com.streeter.domain.engine.RoutingEngine
import com.streeter.domain.model.*
import com.streeter.domain.repository.StreetRepository
import com.streeter.domain.repository.WalkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes street-level and section-level coverage from a matched route.
 *
 * Street identification flow:
 * 1. For each OSM way ID in matchedWayIds, look up the street name
 * 2. Split the way into sections at intersection nodes
 * 3. Compute what fraction of each section was traversed
 * 4. Roll up to street-level percentage (length-weighted)
 */
@Singleton
class StreetCoverageEngine @Inject constructor(
    private val streetRepository: StreetRepository,
    private val walkRepository: WalkRepository,
    private val routingEngine: RoutingEngine
) {

    private data class WayResult(
        val streetId: Long,
        val streetName: String,
        val sections: List<StreetSection>,
        val sectionCoverages: Map<String, Float>
    )

    /**
     * Compute coverage for a walk given its matched way IDs and route geometry.
     * Persists results to [StreetRepository].
     *
     * Multiple OSM ways can share the same street name. We process each way independently,
     * then aggregate all ways with the same name into a single [WalkStreetCoverage] record
     * before persisting — so each named street appears exactly once.
     */
    suspend fun computeAndPersistCoverage(
        walkId: Long,
        matchedWayIds: List<Long>,
        routeGeometryJson: String
    ) = withContext(Dispatchers.IO) {
        Timber.d("Computing coverage for walk=$walkId, ways=${matchedWayIds.size}")

        // Delete existing coverage for this walk (recalculation scenario)
        streetRepository.deleteWalkCoverageForWalk(walkId)

        // Process each way and collect intermediate results
        val wayResults = matchedWayIds.distinct().mapNotNull { wayId ->
            processWayId(wayId, walkId)
        }

        // Aggregate ways that share the same street name into one coverage record
        val byStreetName = wayResults.groupBy { it.streetName }
        for ((streetName, results) in byStreetName) {
            val allSections = results.flatMap { it.sections }
            val allCoverages = results.fold(emptyMap<String, Float>()) { acc, r -> acc + r.sectionCoverages }
            val streetPct = streetCoverage(allSections, allCoverages)
            val representativeStreetId = results.first().streetId

            streetRepository.insertWalkStreetCoverage(
                WalkStreetCoverage(
                    walkId = walkId,
                    streetId = representativeStreetId,
                    streetName = streetName,
                    coveragePct = streetPct
                )
            )
        }

        Timber.d("Coverage computed for ${byStreetName.size} streets (from ${wayResults.size} ways)")
    }

    private suspend fun processWayId(wayId: Long, walkId: Long): WayResult? {
        val streetName = routingEngine.getStreetName(wayId) ?: "Way $wayId"
        val now = System.currentTimeMillis()
        val nameHash = md5(streetName)

        val streetId = streetRepository.upsertStreet(
            Street(
                osmWayId = wayId,
                name = streetName,
                cityTotalLengthM = 100.0, // Placeholder
                osmDataVersion = now,
                osmNameHash = nameHash
            )
        )

        // Create a single section for this way (placeholder: real impl splits at intersections)
        val stableId = generateStableId(streetName, wayId, wayId + 1)
        val existingSection = streetRepository.getSectionByStableId(stableId)

        if (existingSection == null) {
            streetRepository.upsertSection(
                StreetSection(
                    streetId = streetId,
                    fromNodeOsmId = wayId,
                    toNodeOsmId = wayId + 1,
                    lengthM = 100.0,
                    geometryJson = "",
                    stableId = stableId,
                    isOrphaned = false
                )
            )
        }

        // For now, mark this section as 100% covered since the route passed through it
        val coverage = 1.0f
        streetRepository.insertWalkSectionCoverage(
            WalkSectionCoverage(walkId = walkId, sectionStableId = stableId, coveredPct = coverage)
        )

        val sections = streetRepository.getSectionsByStreetId(streetId)
        return WayResult(
            streetId = streetId,
            streetName = streetName,
            sections = sections,
            sectionCoverages = mapOf(stableId to coverage)
        )
    }

    /**
     * Street-level rollup: length-weighted average of section coverages.
     */
    fun streetCoverage(sections: List<StreetSection>, coverages: Map<String, Float>): Float {
        val totalLen = sections.sumOf { it.lengthM }
        if (totalLen == 0.0) return 0f
        val coveredLen = sections.sumOf { it.lengthM * (coverages[it.stableId] ?: 0f) }
        return (coveredLen / totalLen).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Stable section ID: SHA-256(streetName + fromNodeId + toNodeId), truncated to 16 hex chars.
     * Survives OSM PK reassignments across data refreshes.
     */
    fun generateStableId(streetName: String, fromNodeId: Long, toNodeId: Long): String {
        val input = "$streetName|$fromNodeId|$toNodeId"
        return md5(input).take(16)
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
