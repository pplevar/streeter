package com.streeter.data.engine

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
    private val walkRepository: WalkRepository
) {

    /**
     * Compute coverage for a walk given its matched way IDs and route geometry.
     * Persists results to [StreetRepository].
     */
    suspend fun computeAndPersistCoverage(
        walkId: Long,
        matchedWayIds: List<Long>,
        routeGeometryJson: String
    ) = withContext(Dispatchers.IO) {
        Timber.d("Computing coverage for walk=$walkId, ways=${matchedWayIds.size}")

        // Delete existing coverage for this walk (recalculation scenario)
        streetRepository.deleteWalkCoverageForWalk(walkId)

        // Group coverage by street
        val streetCoverageMap = mutableMapOf<Long, Float>()

        for (wayId in matchedWayIds.distinct()) {
            processWayId(wayId, walkId, routeGeometryJson, streetCoverageMap)
        }

        Timber.d("Coverage computed for ${streetCoverageMap.size} streets")
    }

    private suspend fun processWayId(
        wayId: Long,
        walkId: Long,
        routeGeometryJson: String,
        streetCoverageMap: MutableMap<Long, Float>
    ) {
        // Upsert street entity (in a real impl, pull from GraphHopper's graph storage)
        // For Phase 4, we create a minimal street record based on the way ID
        val streetName = "Way $wayId" // Placeholder: real impl reads OSM name tag
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

        // Street-level rollup
        val sections = streetRepository.getSectionsByStreetId(streetId)
        val streetPct = streetCoverage(sections, mapOf(stableId to coverage))
        streetCoverageMap[streetId] = streetPct

        streetRepository.insertWalkStreetCoverage(
            WalkStreetCoverage(
                walkId = walkId,
                streetId = streetId,
                streetName = streetName,
                coveragePct = streetPct
            )
        )
    }

    /**
     * Section-level coverage: fraction of the section's length traversed.
     * Matched edge IDs are used as a proxy; in a full implementation, each edge
     * maps to a measured length on the section.
     */
    fun sectionCoverage(section: StreetSection, matchedEdgeIds: Set<Long>): Float {
        // Simplified: section is fully covered if its fromNode appears in matchedEdgeIds
        return if (section.fromNodeOsmId in matchedEdgeIds) 1.0f else 0.0f
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
