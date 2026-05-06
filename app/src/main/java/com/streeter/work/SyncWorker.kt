package com.streeter.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.streeter.domain.model.SyncStatus
import com.streeter.domain.repository.RemoteSyncRepository
import com.streeter.domain.repository.WalkRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

@HiltWorker
class SyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val remoteSyncRepository: RemoteSyncRepository,
        private val walkRepository: WalkRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val walkId = inputData.getLong(KEY_WALK_ID, -1L)
            if (walkId == -1L) return Result.failure()

            return remoteSyncRepository.syncWalk(walkId).fold(
                onSuccess = { Result.success() },
                onFailure = { throwable ->
                    Timber.w(throwable, "Sync failed for walk $walkId, attempt $runAttemptCount")
                    walkRepository.updateSyncStatus(walkId, SyncStatus.SYNC_FAILED, null)
                    if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
                },
            )
        }

        companion object {
            const val KEY_WALK_ID = "walk_id"
            private const val MAX_RETRIES = 3

            fun buildRequest(walkId: Long): OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInputData(workDataOf(KEY_WALK_ID to walkId))
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag("sync_walk_$walkId")
                    .build()
        }
    }
