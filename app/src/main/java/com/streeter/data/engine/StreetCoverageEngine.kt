package com.streeter.data.engine

import com.streeter.domain.engine.RoutingEngine
import com.streeter.domain.model.*
import com.streeter.domain.repository.StreetRepository
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
 * 1. For each OSM way ID in matchedWayIds, look up the street name and real edge length
 * 2. Accumulate walked length per street name
 * 3. Compute coverage % = walkedLength / totalStreetLength (from GraphHopper index)
 * 4. Persist WalkStreetCoverage records with accurate percentages and walked distances
 */
@Singleton
class StreetCoverageEngine
    @Inject
    constructor(
        private val streetRepository: StreetRepository,
        private val routingEngine: RoutingEngine,
    ) {
        private data class WayResult(
            val streetId: Long,
            val streetName: String,
            val edgeLengthM: Double,
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
            onProgress: (suspend (processed: Int, total: Int) -> Unit)? = null,
        ) = withContext(Dispatchers.IO) {
            Timber.d("Computing coverage for walk=$walkId, ways=${matchedWayIds.size}")

            // Delete existing coverage for this walk (recalculation scenario)
            streetRepository.deleteWalkCoverageForWalk(walkId)

            // Reuse a single MessageDigest to avoid per-call allocation overhead.
            val md5Digest = MessageDigest.getInstance("MD5")

            // Process each way and collect intermediate results
            val distinctWayIds = matchedWayIds.distinct()
            val total = distinctWayIds.size
            val wayResults = ArrayList<WayResult>(total)
            for ((index, wayId) in distinctWayIds.withIndex()) {
                wayResults += processWayId(wayId, walkId, md5Digest)
                if (index % 20 == 19 || index == total - 1) {
                    onProgress?.invoke(index + 1, total)
                }
            }

            // Aggregate ways that share the same street name into one coverage record
            val byStreetName = wayResults.groupBy { it.streetName }
            for ((streetName, results) in byStreetName) {
                val walkedLengthM = results.sumOf { it.edgeLengthM }
                val totalLengthM = routingEngine.getStreetTotalLength(streetName) ?: walkedLengthM
                val coveragePct = (walkedLengthM / totalLengthM).toFloat().coerceIn(0f, 1f)
                val representativeStreetId = results.first().streetId

                streetRepository.insertWalkStreetCoverage(
                    WalkStreetCoverage(
                        walkId = walkId,
                        streetId = representativeStreetId,
                        streetName = streetName,
                        coveragePct = coveragePct,
                        walkedLengthM = walkedLengthM,
                    ),
                )
            }

            Timber.d("Coverage computed for ${byStreetName.size} streets (from ${wayResults.size} ways)")
        }

        private suspend fun processWayId(
            wayId: Long,
            walkId: Long,
            md5Digest: MessageDigest,
        ): WayResult {
            val streetName =
                routingEngine.getStreetName(wayId)
                    ?: routingEngine.findNearestNamedStreet(wayId)
                    ?: "Way $wayId"
            val edgeLengthM = routingEngine.getEdgeLength(wayId) ?: 100.0
            val totalLengthM = routingEngine.getStreetTotalLength(streetName) ?: edgeLengthM
            val now = System.currentTimeMillis()
            val nameHash = md5(streetName, md5Digest)

            val streetId =
                streetRepository.upsertStreet(
                    Street(
                        osmWayId = wayId,
                        name = streetName,
                        cityTotalLengthM = totalLengthM,
                        osmDataVersion = now,
                        osmNameHash = nameHash,
                    ),
                )

            val stableId = generateStableId(streetName, wayId, wayId + 1, md5Digest)
            val existingSection = streetRepository.getSectionByStableId(stableId)

            if (existingSection == null) {
                streetRepository.upsertSection(
                    StreetSection(
                        streetId = streetId,
                        fromNodeOsmId = wayId,
                        toNodeOsmId = wayId + 1,
                        lengthM = edgeLengthM,
                        geometryJson = "",
                        stableId = stableId,
                        isOrphaned = false,
                    ),
                )
            }

            streetRepository.insertWalkSectionCoverage(
                WalkSectionCoverage(walkId = walkId, sectionStableId = stableId, coveredPct = 1.0f),
            )

            return WayResult(
                streetId = streetId,
                streetName = streetName,
                edgeLengthM = edgeLengthM,
            )
        }

        /**
         * Length-weighted average of section coverages. Exposed for unit testing.
         */
        fun streetCoverage(
            sections: List<StreetSection>,
            coverages: Map<String, Float>,
        ): Float {
            val totalLen = sections.sumOf { it.lengthM }
            if (totalLen == 0.0) return 0f
            val coveredLen = sections.sumOf { it.lengthM * (coverages[it.stableId] ?: 0f) }
            return (coveredLen / totalLen).toFloat().coerceIn(0f, 1f)
        }

        /**
         * Stable section ID: MD5(streetName + fromNodeId + toNodeId), truncated to 16 hex chars.
         * Survives OSM PK reassignments across data refreshes.
         */
        fun generateStableId(
            streetName: String,
            fromNodeId: Long,
            toNodeId: Long,
        ): String {
            return generateStableId(streetName, fromNodeId, toNodeId, MessageDigest.getInstance("MD5"))
        }

        private fun generateStableId(
            streetName: String,
            fromNodeId: Long,
            toNodeId: Long,
            md5Digest: MessageDigest,
        ): String {
            val input = "$streetName|$fromNodeId|$toNodeId"
            return md5(input, md5Digest).take(16)
        }

        private fun md5(
            input: String,
            digest: MessageDigest,
        ): String {
            digest.reset()
            val bytes = digest.digest(input.toByteArray())
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                sb.append(HEX_CHARS[(b.toInt() shr 4) and 0x0F])
                sb.append(HEX_CHARS[b.toInt() and 0x0F])
            }
            return sb.toString()
        }

        companion object {
            private val HEX_CHARS = "0123456789abcdef".toCharArray()
        }
    }
