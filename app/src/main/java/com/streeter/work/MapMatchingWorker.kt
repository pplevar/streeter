package com.streeter.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.streeter.data.engine.StreetCoverageEngine
import com.streeter.domain.engine.RoutingEngine
import com.streeter.domain.geometry.MalformedGeometryException
import com.streeter.domain.geometry.TraceGeometry
import com.streeter.domain.model.JobStatus
import com.streeter.domain.model.RouteSegment
import com.streeter.domain.model.WalkSource
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.repository.GpsPointRepository
import com.streeter.domain.repository.PendingMatchJobRepository
import com.streeter.domain.repository.RouteSegmentRepository
import com.streeter.domain.repository.WalkRepository
import com.streeter.domain.work.WalkCalculationFinalizer
import com.streeter.domain.work.WorkRetryPolicy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

@HiltWorker
class MapMatchingWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted workerParams: WorkerParameters,
        private val walkRepository: WalkRepository,
        private val routeSegmentRepository: RouteSegmentRepository,
        private val gpsPointRepository: GpsPointRepository,
        private val pendingMatchJobRepository: PendingMatchJobRepository,
        private val routingEngine: RoutingEngine,
        private val coverageEngine: StreetCoverageEngine,
        private val finalizer: WalkCalculationFinalizer,
    ) : CoroutineWorker(context, workerParams) {
        companion object {
            const val KEY_WALK_ID = "walk_id"
            const val KEY_PROGRESS = "progress"
            const val KEY_STEP = "step"

            fun buildRequest(walkId: Long): OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<MapMatchingWorker>()
                    .setInputData(workDataOf(KEY_WALK_ID to walkId))
                    .setConstraints(Constraints.NONE)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        WorkRetryPolicy.BACKOFF_SECONDS,
                        java.util.concurrent.TimeUnit.SECONDS,
                    )
                    .build()
        }

        override suspend fun doWork(): Result =
            withContext(Dispatchers.IO) {
                val walkId = inputData.getLong(KEY_WALK_ID, -1L)
                if (walkId == -1L) return@withContext Result.failure()

                Timber.w("MapMatchingWorker starting for walk=$walkId")

                pendingMatchJobRepository.getJobForWalk(walkId)?.let {
                    pendingMatchJobRepository.updateJob(it.copy(status = JobStatus.IN_PROGRESS))
                }
                setProgress(workDataOf(KEY_PROGRESS to 5, KEY_STEP to "Starting…"))

                return@withContext try {
                    if (!routingEngine.isReady()) {
                        setProgress(workDataOf(KEY_PROGRESS to 5, KEY_STEP to "Loading map engine (this may take a moment)…"))
                        routingEngine.initialize()
                        Timber.d("MapMatchingWorker: engine initialized for walk=$walkId")
                    }
                    setProgress(workDataOf(KEY_PROGRESS to 10, KEY_STEP to "Loading route data…"))

                    val walk = walkRepository.getWalkById(walkId)
                    if (walk == null) {
                        Timber.w("Walk $walkId not found")
                        return@withContext Result.failure()
                    }
                    if (walk.status == WalkStatus.DELETED) {
                        Timber.w("Walk $walkId is DELETED, aborting map matching")
                        pendingMatchJobRepository.getJobForWalk(walkId)?.let {
                            pendingMatchJobRepository.updateJob(it.copy(status = JobStatus.DONE))
                        }
                        return@withContext Result.failure()
                    }

                    var matchedDistanceM: Double
                    val wayIds: List<Long> =
                        if (walk.source == WalkSource.RECORDED) {
                            // GPS trace → map match → get way IDs
                            val points = gpsPointRepository.getPointsForMapMatching(walkId)
                            Timber.d("MapMatchingWorker: ${points.size} GPS points for walk=$walkId")
                            setProgress(workDataOf(KEY_PROGRESS to 15, KEY_STEP to "Loaded ${points.size} GPS points…"))

                            if (points.size < 2) {
                                Timber.w("Not enough GPS points for walk=$walkId, completing without coverage")
                                finalizer.complete(walkId)
                                return@withContext Result.success()
                            }

                            if (isStopped) return@withContext Result.retry()

                            setProgress(workDataOf(KEY_PROGRESS to 20, KEY_STEP to "Matching route to streets…"))
                            val matchResult =
                                coroutineScope {
                                    launch {
                                        var heartbeatPct = 20
                                        while (heartbeatPct < 48) {
                                            delay(3_000L)
                                            heartbeatPct = (heartbeatPct + 4).coerceAtMost(48)
                                            setProgress(workDataOf(KEY_PROGRESS to heartbeatPct, KEY_STEP to "Matching route to streets…"))
                                        }
                                    }
                                    routingEngine.matchTrace(points)
                                }
                            if (matchResult.isFailure) {
                                Timber.w(
                                    "Map matching failed for walk=%d: %s, completing without coverage",
                                    walkId,
                                    matchResult.exceptionOrNull()?.message,
                                )
                                finalizer.complete(walkId)
                                return@withContext Result.success()
                            }
                            setProgress(workDataOf(KEY_PROGRESS to 50, KEY_STEP to "Route matched…"))

                            val matched = matchResult.getOrThrow()
                            routeSegmentRepository.deleteSegmentsForWalk(walkId)
                            routeSegmentRepository.insertSegment(
                                RouteSegment(
                                    walkId = walkId,
                                    geometryJson = matched.routeGeometryJson,
                                    matchedWayIds = "[${matched.matchedWayIds.joinToString(",")}]",
                                    segmentOrder = 0,
                                ),
                            )
                            matchedDistanceM = matched.distanceM
                            matched.matchedWayIds
                        } else {
                            // Manual walk — use pre-existing segments built by the route editor
                            val segments = routeSegmentRepository.getSegmentsForWalk(walkId)
                            if (segments.isEmpty()) {
                                Timber.w("No segments for manual walk=$walkId, completing without coverage")
                                finalizer.complete(walkId)
                                return@withContext Result.success()
                            }
                            setProgress(workDataOf(KEY_PROGRESS to 50, KEY_STEP to "Route segments loaded…"))
                            // Unreadable geometry raises rather than measuring zero, so a manual
                            // walk never reports a length it did not have. Caught below: the walk
                            // still finishes, but without a distance and with the reason recorded.
                            matchedDistanceM = segments.sumOf { TraceGeometry.lengthMeters(it.geometryJson) }
                            segments.flatMap { parseWayIds(it.matchedWayIds) }
                        }

                    setProgress(workDataOf(KEY_PROGRESS to 50, KEY_STEP to "Computing street coverage…"))
                    coverageEngine.computeAndPersistCoverage(
                        walkId = walkId,
                        matchedWayIds = wayIds,
                        onProgress = { processed, total ->
                            val pct = 50 + ((processed.toFloat() / total) * 40).toInt()
                            setProgress(workDataOf(KEY_PROGRESS to pct, KEY_STEP to "Computing coverage ($processed/$total streets)…"))
                        },
                    )

                    setProgress(workDataOf(KEY_PROGRESS to 95, KEY_STEP to "Finalizing…"))
                    finalizer.complete(walkId, matchedDistanceM.takeIf { it > 0.0 })
                    pendingMatchJobRepository.getJobForWalk(walkId)?.let {
                        pendingMatchJobRepository.updateJob(it.copy(status = JobStatus.DONE))
                    }

                    Timber.i("MapMatchingWorker completed for walk=$walkId")
                    Result.success()
                } catch (e: MalformedGeometryException) {
                    // Geometry that cannot be read will not read on the next attempt either, so
                    // this is a dead end rather than a retry: finish the walk without a distance
                    // and leave the reason on the job, instead of stranding it in PENDING_MATCH.
                    Timber.e(e, "Unreadable segment geometry for walk=$walkId, completing without a distance")
                    finalizer.complete(walkId)
                    pendingMatchJobRepository.getJobForWalk(walkId)?.let {
                        pendingMatchJobRepository.updateJob(
                            it.copy(status = JobStatus.DONE, lastError = "Unreadable geometry: ${e.message}"),
                        )
                    }
                    Result.success()
                } catch (e: java.io.FileNotFoundException) {
                    Timber.w("MapMatchingWorker: engine assets missing for walk=$walkId, completing without coverage")
                    finalizer.complete(walkId)
                    pendingMatchJobRepository.getJobForWalk(walkId)?.let {
                        pendingMatchJobRepository.updateJob(
                            it.copy(status = JobStatus.DONE, lastError = "No map assets: ${e.message}"),
                        )
                    }
                    Result.success()
                } catch (e: CancellationException) {
                    // WorkerStoppedException (subclass of CancellationException) means the OS stopped
                    // this job. Do NOT catch it as a retryable error — WorkManager handles rescheduling
                    // internally. Swallowing it and returning Result.retry() causes an infinite restart loop.
                    Timber.w("MapMatchingWorker cancelled by OS for walk=$walkId; will be rescheduled")
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "MapMatchingWorker failed for walk=$walkId")
                    val retries = runAttemptCount
                    pendingMatchJobRepository.getJobForWalk(walkId)?.let {
                        pendingMatchJobRepository.updateJob(
                            it.copy(
                                status = if (WorkRetryPolicy.hasAttemptsLeft(retries)) JobStatus.QUEUED else JobStatus.FAILED,
                                retryCount = retries,
                                lastError = e.message,
                            ),
                        )
                    }
                    if (WorkRetryPolicy.hasAttemptsLeft(retries)) Result.retry() else Result.failure()
                }
            }

        private fun parseWayIds(json: String): List<Long> {
            return try {
                Json.parseToJsonElement(json).jsonArray.map { it.jsonPrimitive.content.toLong() }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
