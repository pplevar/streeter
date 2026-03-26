package com.streeter.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.streeter.data.engine.StreetCoverageEngine
import com.streeter.domain.engine.RoutingEngine
import com.streeter.domain.model.JobStatus
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.repository.PendingMatchJobRepository
import com.streeter.domain.repository.RouteSegmentRepository
import com.streeter.domain.repository.WalkRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
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

        Timber.d("MapMatchingWorker starting for walk=$walkId")

        // Update job status
        pendingMatchJobRepository.getJobForWalk(walkId)?.let {
            pendingMatchJobRepository.updateJob(it.copy(status = JobStatus.IN_PROGRESS))
        }

        return@withContext try {
            // Ensure engine is ready
            if (!routingEngine.isReady()) {
                routingEngine.initialize()
            }

            val segments = routeSegmentRepository.getSegmentsForWalk(walkId)
            if (segments.isEmpty()) {
                Timber.w("No route segments found for walk=$walkId")
                pendingMatchJobRepository.getJobForWalk(walkId)?.let {
                    pendingMatchJobRepository.updateJob(
                        it.copy(status = JobStatus.FAILED, lastError = "No route segments")
                    )
                }
                return@withContext Result.failure()
            }
            val segment = segments.first()
            val wayIds = parseWayIds(segment.matchedWayIds)

            coverageEngine.computeAndPersistCoverage(
                walkId = walkId,
                matchedWayIds = wayIds
            )

            // Update walk status to COMPLETED
            walkRepository.getWalkById(walkId)?.let { walk ->
                walkRepository.updateWalk(
                    walk.copy(status = WalkStatus.COMPLETED, updatedAt = System.currentTimeMillis())
                )
            }

            // Mark job done
            pendingMatchJobRepository.getJobForWalk(walkId)?.let {
                pendingMatchJobRepository.updateJob(it.copy(status = JobStatus.DONE))
            }

            Timber.i("MapMatchingWorker completed for walk=$walkId")
            Result.success()
        } catch (e: java.io.FileNotFoundException) {
            // Engine assets are missing (e.g. OSM PBF not bundled). This won't resolve
            // on retry, so complete the walk without coverage data.
            Timber.w("MapMatchingWorker: engine assets missing for walk=$walkId, completing without coverage")
            walkRepository.getWalkById(walkId)?.let { walk ->
                walkRepository.updateWalk(
                    walk.copy(status = WalkStatus.COMPLETED, updatedAt = System.currentTimeMillis())
                )
            }
            pendingMatchJobRepository.getJobForWalk(walkId)?.let {
                pendingMatchJobRepository.updateJob(
                    it.copy(status = JobStatus.DONE, lastError = "No map assets: ${e.message}")
                )
            }
            Result.success()
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

    private fun parseWayIds(json: String): List<Long> {
        return try {
            Json.parseToJsonElement(json).jsonArray.map { it.jsonPrimitive.content.toLong() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
