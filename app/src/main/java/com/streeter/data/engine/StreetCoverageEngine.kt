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
        private val transactionRunner: TransactionRunner,
    ) {
        private data class WayInfo(
            val wayId: Long,
            val streetName: String,
            val edgeLengthM: Double,
            val totalLengthM: Double,
            val stableId: String,
            val nameHash: String,
            val osmDataVersion: Long,
        )

        /**
         * Compute coverage for a walk given its matched way IDs and route geometry.
         * Persists results to [StreetRepository].
         *
         * Multiple OSM ways can share the same street name. We process each way independently,
         * then aggregate all ways with the same name into a single [WalkStreetCoverage] record
         * before persisting — so each named street appears exactly once.
         *
         * Performance: all DB writes are batched into a single transaction to avoid per-row
         * fsyncs, which reduces wall-clock time from minutes to seconds on typical walks.
         */
        suspend fun computeAndPersistCoverage(
            walkId: Long,
            matchedWayIds: List<Long>,
            onProgress: (suspend (processed: Int, total: Int) -> Unit)? = null,
        ) = withContext(Dispatchers.IO) {
            Timber.d("Computing coverage for walk=$walkId, ways=${matchedWayIds.size}")

            // Pre-warm the street length index so the O(E) graph scan doesn't stall the first
            // iteration of Phase 1.
            routingEngine.getStreetTotalLength("__warmup__")

            val md5Digest = MessageDigest.getInstance("MD5")
            val distinctWayIds = matchedWayIds.distinct()
            val total = distinctWayIds.size
            val osmDataVersion = System.currentTimeMillis()

            // Phase 1: CPU-only — resolve all street info from the in-memory graph (no DB I/O)
            val wayInfoList = ArrayList<WayInfo>(total)
            for ((index, wayId) in distinctWayIds.withIndex()) {
                val streetName =
                    routingEngine.getStreetName(wayId)
                        ?: routingEngine.findNearestNamedStreet(wayId)
                        ?: "Way $wayId"
                val edgeLengthM = routingEngine.getEdgeLength(wayId) ?: 100.0
                val totalLengthM = routingEngine.getStreetTotalLength(streetName) ?: edgeLengthM
                val stableId = generateStableId(streetName, wayId, wayId + 1, md5Digest)
                val nameHash = md5(streetName, md5Digest)
                wayInfoList += WayInfo(wayId, streetName, edgeLengthM, totalLengthM, stableId, nameHash, osmDataVersion)
                if (index % 20 == 19 || index == total - 1) {
                    onProgress?.invoke(index + 1, total)
                }
            }

            // Phase 2: Single batch read — find which sections already exist in the DB
            val stableIds = wayInfoList.map { it.stableId }
            val existingStableIds =
                streetRepository.getSectionsByStableIds(stableIds)
                    .map { it.stableId }
                    .toHashSet()

            // Phase 3: All DB writes in a single transaction (one fsync instead of hundreds)
            transactionRunner.run {
                streetRepository.deleteWalkCoverageForWalk(walkId)

                val streetIds =
                    streetRepository.upsertStreets(
                        wayInfoList.map { wi ->
                            Street(
                                osmWayId = wi.wayId,
                                name = wi.streetName,
                                cityTotalLengthM = wi.totalLengthM,
                                osmDataVersion = wi.osmDataVersion,
                                osmNameHash = wi.nameHash,
                            )
                        },
                    )

                val newSections =
                    wayInfoList.zip(streetIds)
                        .filter { (wi, _) -> wi.stableId !in existingStableIds }
                        .map { (wi, streetId) ->
                            StreetSection(
                                streetId = streetId,
                                fromNodeOsmId = wi.wayId,
                                toNodeOsmId = wi.wayId + 1,
                                lengthM = wi.edgeLengthM,
                                geometryJson = "",
                                stableId = wi.stableId,
                                isOrphaned = false,
                            )
                        }
                if (newSections.isNotEmpty()) {
                    streetRepository.upsertSections(newSections)
                }

                streetRepository.insertWalkSectionCoverages(
                    wayInfoList.map { wi ->
                        WalkSectionCoverage(walkId = walkId, sectionStableId = wi.stableId, coveredPct = 1.0f)
                    },
                )

                val byStreetName = wayInfoList.zip(streetIds).groupBy { (wi, _) -> wi.streetName }
                streetRepository.insertWalkStreetCoverages(
                    byStreetName.map { (streetName, pairs) ->
                        val walkedLengthM = pairs.sumOf { (wi, _) -> wi.edgeLengthM }
                        val totalLengthM = routingEngine.getStreetTotalLength(streetName) ?: walkedLengthM
                        val coveragePct = (walkedLengthM / totalLengthM).toFloat().coerceIn(0f, 1f)
                        WalkStreetCoverage(
                            walkId = walkId,
                            streetId = pairs.first().second,
                            streetName = streetName,
                            coveragePct = coveragePct,
                            walkedLengthM = walkedLengthM,
                        )
                    },
                )
            }

            Timber.d("Coverage computed for ${wayInfoList.groupBy { it.streetName }.size} streets (from ${wayInfoList.size} ways)")
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
