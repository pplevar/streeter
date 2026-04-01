package com.streeter.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.streeter.data.engine.StreetCoverageEngine
import com.streeter.domain.engine.RoutingEngine
import com.streeter.domain.model.JobStatus
import com.streeter.domain.model.RouteSegment
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
class MapMatchingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val walkRepository: WalkRepository,
    private val routeSegmentRepository: RouteSegmentRepository,
    private val gpsPointRepository: GpsPointRepository,
    private val pendingMatchJobRepository: PendingMatchJobRepository,
    private val routingEngine: RoutingEngine,
    private val coverageEngine: StreetCoverageEngine
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val KEY_WALK_ID = "walk_id"

        fun buildRequest(walkId: Long): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<MapMatchingWorker>()
                .setInputData(workDataOf(KEY_WALK_ID to walkId))
                .setConstraints(Constraints.NONE)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val walkId = inputData.getLong(KEY_WALK_ID, -1L)
        if (walkId == -1L) return@withContext Result.failure()

        Timber.w("MapMatchingWorker starting for walk=$walkId")

        pendingMatchJobRepository.getJobForWalk(walkId)?.let {
            pendingMatchJobRepository.updateJob(it.copy(status = JobStatus.IN_PROGRESS))
        }

        return@withContext try {
            if (!routingEngine.isReady()) {
                routingEngine.initialize()
            }

            val walk = walkRepository.getWalkById(walkId)
            if (walk == null) {
                Timber.w("Walk $walkId not found")
                return@withContext Result.failure()
            }

            val wayIds: List<Long> = if (walk.source == WalkSource.RECORDED) {
                // GPS trace → map match → get way IDs
                val points = gpsPointRepository.getPointsForWalk(walkId).filter { !it.isFiltered }
                Timber.d("MapMatchingWorker: ${points.size} GPS points for walk=$walkId")

                if (points.size < 2) {
                    Timber.w("Not enough GPS points for walk=$walkId, completing without coverage")
                    completeWalk(walkId)
                    return@withContext Result.success()
                }

                if (isStopped) return@withContext Result.retry()

                val matchResult = routingEngine.matchTrace(points)
                if (matchResult.isFailure) {
                    Timber.w("Map matching failed for walk=$walkId: ${matchResult.exceptionOrNull()?.message}, completing without coverage")
                    completeWalk(walkId)
                    return@withContext Result.success()
                }

                val matched = matchResult.getOrThrow()
                // Persist segment so it can be referenced later (e.g. route editing)
                routeSegmentRepository.insertSegment(
                    RouteSegment(
                        walkId = walkId,
                        geometryJson = matched.routeGeometryJson,
                        matchedWayIds = "[${matched.matchedWayIds.joinToString(",")}]",
                        segmentOrder = 0
                    )
                )
                matched.matchedWayIds
            } else {
                // Manual walk — use pre-existing segments built by the route editor
                val segments = routeSegmentRepository.getSegmentsForWalk(walkId)
                if (segments.isEmpty()) {
                    Timber.w("No segments for manual walk=$walkId, completing without coverage")
                    completeWalk(walkId)
                    return@withContext Result.success()
                }
                segments.flatMap { parseWayIds(it.matchedWayIds) }
            }

            coverageEngine.computeAndPersistCoverage(
                walkId = walkId,
                matchedWayIds = wayIds
            )

            completeWalk(walkId)
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
                    it.copy(status = JobStatus.DONE, lastError = "No map assets: ${e.message}")
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
                        lastError = e.message
                    )
                )
            }
            if (retries >= 3) Result.failure() else Result.retry()
        }
    }

    private suspend fun completeWalk(walkId: Long) {
        walkRepository.getWalkById(walkId)?.let { walk ->
            walkRepository.updateWalk(
                walk.copy(status = WalkStatus.COMPLETED, updatedAt = System.currentTimeMillis())
            )
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
