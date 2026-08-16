package com.streeter.domain.calculation

import com.streeter.domain.engine.CoverageEngine
import com.streeter.domain.engine.RoutingEngine
import com.streeter.domain.geometry.MalformedGeometryException
import com.streeter.domain.geometry.TraceGeometry
import com.streeter.domain.model.JobStatus
import com.streeter.domain.model.PendingMatchJob
import com.streeter.domain.model.RouteSegment
import com.streeter.domain.model.WalkSource
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.repository.GpsPointRepository
import com.streeter.domain.repository.PendingMatchJobRepository
import com.streeter.domain.repository.RouteSegmentRepository
import com.streeter.domain.repository.WalkRepository
import com.streeter.domain.work.WalkCalculationFinalizer
import com.streeter.domain.work.WorkRetryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.FileNotFoundException
import javax.inject.Inject

/** What the driver must report back to WorkManager once Calculation has run (issue #62). */
enum class CalculationDisposition { SUCCESS, RETRY, FAILURE }

/**
 * The Android side of one Calculation run, as the session needs it: somewhere to publish
 * progress, and a way to learn the OS has stopped the job.
 *
 * The worker implements it; a test can implement it in three lines.
 */
interface CalculationDriver {
    /** Publish [percent] complete and the user-facing [step] it is on. */
    suspend fun report(
        percent: Int,
        step: String,
    )

    /** True once WorkManager has stopped this run and the work should be handed back. */
    val isStopped: Boolean
        get() = false

    /**
     * Runs the one long call of Calculation — map matching — so the driver can publish progress
     * alongside it. The session does not care how (or whether) that happens.
     */
    suspend fun <T> whileMatching(block: suspend () -> T): T = block()
}

/**
 * What it means to calculate a walk (issue #62).
 *
 * The worker owns the Android side — WorkManager's result type, the progress channel, the
 * heartbeat coroutine — and drives this for every decision:
 *
 * - **Recorded versus manual dispatch.** A Recorded Walk's ways come from map-matching its GPS
 *   Trace; a Manual Walk's come from the segments the route editor already built. This fork is
 *   Calculation's central shape and exists nowhere else.
 * - **Graceful degradation.** Too few points, matching failed, no manual segments, unreadable
 *   geometry, missing map assets — each is a dead end that no retry can fix, so the walk
 *   *completes without Coverage* rather than being stranded in PENDING_MATCH. A walk with no
 *   Coverage is still a finished walk.
 * - **Abort when the walk was deleted mid-run.** Deletion drops the walk's Coverage; a
 *   Calculation still in flight must not write more of it back.
 * - **The retry budget**, shared with Sync via [WorkRetryPolicy] rather than counted twice.
 */
class CalculationSession
    @Inject
    constructor(
        private val walkRepository: WalkRepository,
        private val gpsPointRepository: GpsPointRepository,
        private val routeSegmentRepository: RouteSegmentRepository,
        private val pendingMatchJobRepository: PendingMatchJobRepository,
        private val routingEngine: RoutingEngine,
        private val coverageEngine: CoverageEngine,
        private val finalizer: WalkCalculationFinalizer,
    ) {
        /** Where a walk's matched ways came from, or why there are none. */
        private sealed interface Ways {
            /** The ways to compute Coverage over, and the length they were measured along. */
            data class Matched(val wayIds: List<Long>, val distanceM: Double) : Ways

            /** A dead end: this walk has no Coverage to compute and never will. */
            data class None(val reason: String) : Ways

            /** The OS stopped the job mid-run; nothing was decided. */
            data object Stopped : Ways
        }

        /**
         * Calculate [walkId], reporting progress through [driver], and say what the driver should
         * report back to WorkManager. [runAttemptCount] is WorkManager's attempt counter, spent
         * against the shared retry budget.
         */
        suspend fun calculate(
            walkId: Long,
            runAttemptCount: Int,
            driver: CalculationDriver,
        ): CalculationDisposition {
            updateJob(walkId) { it.copy(status = JobStatus.IN_PROGRESS) }
            driver.report(5, "Starting…")

            return try {
                if (!routingEngine.isReady()) {
                    driver.report(5, "Loading map engine (this may take a moment)…")
                    routingEngine.initialize()
                }
                driver.report(10, "Loading route data…")

                val walk = walkRepository.getWalkById(walkId) ?: return CalculationDisposition.FAILURE
                if (walk.status == WalkStatus.DELETED) {
                    // The walk was deleted while this run was in flight: its Coverage is already
                    // gone, so writing more of it would resurrect rows for a walk that no longer
                    // exists. Close the job and stop.
                    updateJob(walkId) { it.copy(status = JobStatus.DONE) }
                    return CalculationDisposition.FAILURE
                }

                val ways =
                    if (walk.source == WalkSource.RECORDED) {
                        matchRecordedTrace(walkId, driver)
                    } else {
                        loadManualSegments(walkId, driver)
                    }

                when (ways) {
                    is Ways.Stopped -> return CalculationDisposition.RETRY
                    is Ways.None -> return completeWithoutCoverage(walkId, ways.reason)
                    is Ways.Matched -> {
                        driver.report(50, "Computing street coverage…")
                        coverageEngine.computeAndPersistCoverage(
                            walkId = walkId,
                            matchedWayIds = ways.wayIds,
                            onProgress = { processed, total ->
                                val pct = 50 + ((processed.toFloat() / total) * 40).toInt()
                                driver.report(pct, "Computing coverage ($processed/$total streets)…")
                            },
                        )
                        driver.report(95, "Finalizing…")
                        finalizer.complete(walkId, ways.distanceM.takeIf { it > 0.0 })
                        updateJob(walkId) { it.copy(status = JobStatus.DONE) }
                        CalculationDisposition.SUCCESS
                    }
                }
            } catch (e: MalformedGeometryException) {
                // Geometry that cannot be read will not read on the next attempt either, so this
                // is a dead end rather than a retry: finish the walk without a distance and leave
                // the reason on the job, instead of stranding it in PENDING_MATCH.
                completeWithoutCoverage(walkId, "Unreadable geometry: ${e.message}")
            } catch (e: FileNotFoundException) {
                // No map assets on this device — retrying cannot conjure them up.
                completeWithoutCoverage(walkId, "No map assets: ${e.message}")
            } catch (e: CancellationException) {
                // WorkerStoppedException (a CancellationException) means the OS stopped this job.
                // WorkManager reschedules it itself; swallowing this would loop forever.
                throw e
            } catch (e: Exception) {
                updateJob(walkId) {
                    it.copy(
                        status = if (WorkRetryPolicy.hasAttemptsLeft(runAttemptCount)) JobStatus.QUEUED else JobStatus.FAILED,
                        retryCount = runAttemptCount,
                        lastError = e.message,
                    )
                }
                if (WorkRetryPolicy.hasAttemptsLeft(runAttemptCount)) {
                    CalculationDisposition.RETRY
                } else {
                    CalculationDisposition.FAILURE
                }
            }
        }

        /** A Recorded Walk's ways: snap its GPS Trace onto the street network. */
        private suspend fun matchRecordedTrace(
            walkId: Long,
            driver: CalculationDriver,
        ): Ways {
            val points = gpsPointRepository.getPointsForMapMatching(walkId)
            driver.report(15, "Loaded ${points.size} GPS points…")
            if (points.size < 2) return Ways.None("Not enough GPS points to match")

            if (driver.isStopped) return Ways.Stopped

            driver.report(20, "Matching route to streets…")
            val matchResult = driver.whileMatching { routingEngine.matchTrace(points) }
            val matched =
                matchResult.getOrElse { cause ->
                    return Ways.None("Map matching failed: ${cause.message}")
                }
            driver.report(50, "Route matched…")

            // The matched route replaces whatever a previous run left behind, so a recalculation
            // never draws two routes for one walk.
            routeSegmentRepository.deleteSegmentsForWalk(walkId)
            routeSegmentRepository.insertSegment(
                RouteSegment(
                    walkId = walkId,
                    geometryJson = matched.routeGeometryJson,
                    matchedWayIds = "[${matched.matchedWayIds.joinToString(",")}]",
                    segmentOrder = 0,
                ),
            )
            return Ways.Matched(matched.matchedWayIds, matched.distanceM)
        }

        /** A Manual Walk's ways: the segments the route editor already built. */
        private suspend fun loadManualSegments(
            walkId: Long,
            driver: CalculationDriver,
        ): Ways {
            val segments = routeSegmentRepository.getSegmentsForWalk(walkId)
            if (segments.isEmpty()) return Ways.None("No route segments for a manual walk")
            driver.report(50, "Route segments loaded…")
            // Unreadable geometry raises rather than measuring zero, so a manual walk never
            // reports a length it did not have. Caught above: the walk still finishes, but
            // without a distance and with the reason recorded.
            val distanceM = segments.sumOf { TraceGeometry.lengthMeters(it.geometryJson) }
            return Ways.Matched(segments.flatMap { parseWayIds(it.matchedWayIds) }, distanceM)
        }

        /**
         * The walk finishes anyway — COMPLETED, with whatever distance it already carried and no
         * Coverage — and [reason] is left on the job so the dead end is visible afterwards.
         */
        private suspend fun completeWithoutCoverage(
            walkId: Long,
            reason: String,
        ): CalculationDisposition {
            finalizer.complete(walkId)
            updateJob(walkId) { it.copy(status = JobStatus.DONE, lastError = reason) }
            return CalculationDisposition.SUCCESS
        }

        private suspend fun updateJob(
            walkId: Long,
            change: (PendingMatchJob) -> PendingMatchJob,
        ) {
            pendingMatchJobRepository.getJobForWalk(walkId)?.let {
                pendingMatchJobRepository.updateJob(change(it))
            }
        }

        private fun parseWayIds(json: String): List<Long> =
            try {
                Json.parseToJsonElement(json).jsonArray.map { it.jsonPrimitive.content.toLong() }
            } catch (_: Exception) {
                emptyList()
            }
    }
