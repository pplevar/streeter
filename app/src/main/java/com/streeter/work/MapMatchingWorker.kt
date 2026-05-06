package com.streeter.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import androidx.work.WorkManager
import com.streeter.data.engine.StreetCoverageEngine
import com.streeter.domain.engine.RoutingEngine
import com.streeter.domain.model.JobStatus
import com.streeter.domain.model.RouteSegment
import com.streeter.domain.model.SyncStatus
import com.streeter.domain.model.WalkSource
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.repository.GpsPointRepository
import com.streeter.domain.repository.PendingMatchJobRepository
import com.streeter.domain.repository.RouteSegmentRepository
import com.streeter.domain.repository.WalkRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
        private val workManager: WorkManager,
    ) : CoroutineWorker(context, workerParams) {
        companion object {
            const val KEY_WALK_ID = "walk_id"
            const val KEY_PROGRESS = "progress"
            const val KEY_STEP = "step"

            fun buildRequest(walkId: Long): OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<MapMatchingWorker>()
                    .setInputData(workDataOf(KEY_WALK_ID to walkId))
                    .setConstraints(Constraints.NONE)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
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

                    var matchedDistanceM = 0.0
                    val wayIds: List<Long> =
                        if (walk.source == WalkSource.RECORDED) {
                            // GPS trace → map match → get way IDs
                            val points = gpsPointRepository.getPointsForWalk(walkId).filter { !it.isFiltered }
                            Timber.d("MapMatchingWorker: ${points.size} GPS points for walk=$walkId")
                            setProgress(workDataOf(KEY_PROGRESS to 15, KEY_STEP to "Loaded ${points.size} GPS points…"))

                            if (points.size < 2) {
                                Timber.w("Not enough GPS points for walk=$walkId, completing without coverage")
                                completeWalk(walkId)
                                return@withContext Result.success()
                            }

                            if (isStopped) return@withContext Result.retry()

                            setProgress(workDataOf(KEY_PROGRESS to 20, KEY_STEP to "Matching route to streets…"))
                            val matchResult = routingEngine.matchTrace(points)
                            if (matchResult.isFailure) {
                                Timber.w(
                                    "Map matching failed for walk" +
                                        "=$walkId: ${matchResult.exceptionOrNull()?.message}, " +
                                        "completing without coverage",
                                )
                                completeWalk(walkId)
                                return@withContext Result.success()
                            }
                            setProgress(workDataOf(KEY_PROGRESS to 50, KEY_STEP to "Route matched…"))

                            val matched = matchResult.getOrThrow()
                            // Persist segment so it can be referenced later (e.g. route editing)
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
                                completeWalk(walkId)
                                return@withContext Result.success()
                            }
                            setProgress(workDataOf(KEY_PROGRESS to 50, KEY_STEP to "Route segments loaded…"))
                            matchedDistanceM = segments.sumOf { geometryDistanceM(it.geometryJson) }
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
                    completeWalk(walkId, matchedDistanceM.takeIf { it > 0.0 })
                    pendingMatchJobRepository.getJobForWalk(walkId)?.let {
                        pendingMatchJobRepository.updateJob(it.copy(status = JobStatus.DONE))
                    }

                    Timber.i("MapMatchingWorker completed for walk=$walkId")
                    Result.success()
                } catch (e: java.io.FileNotFoundException) {
                    Timber.w("MapMatchingWorker: engine assets missing for walk=$walkId, completing without coverage")
                    completeWalk(walkId)
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
                                status = if (retries >= 3) JobStatus.FAILED else JobStatus.QUEUED,
                                retryCount = retries,
                                lastError = e.message,
                            ),
                        )
                    }
                    if (retries >= 3) Result.failure() else Result.retry()
                }
            }

        private suspend fun completeWalk(
            walkId: Long,
            distanceM: Double? = null,
        ) {
            walkRepository.getWalkById(walkId)?.let { walk ->
                walkRepository.updateWalk(
                    walk.copy(
                        status = WalkStatus.COMPLETED,
                        distanceM = distanceM ?: walk.distanceM,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            walkRepository.updateSyncStatus(walkId, SyncStatus.PENDING_SYNC, null)
            workManager.enqueue(SyncWorker.buildRequest(walkId))
        }

        private fun geometryDistanceM(geometryJson: String): Double {
            return try {
                val obj = org.json.JSONObject(geometryJson)
                val arr = obj.getJSONObject("geometry").getJSONArray("coordinates")
                if (arr.length() < 2) return 0.0
                val results = FloatArray(1)
                var total = 0.0
                for (i in 0 until arr.length() - 1) {
                    val a = arr.getJSONArray(i)
                    val b = arr.getJSONArray(i + 1)
                    android.location.Location.distanceBetween(
                        a.getDouble(1),
                        a.getDouble(0),
                        b.getDouble(1),
                        b.getDouble(0),
                        results,
                    )
                    total += results[0]
                }
                total
            } catch (_: Exception) {
                0.0
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
