package com.streeter.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.streeter.domain.repository.RemoteSyncRepository
import com.streeter.domain.work.WalkSyncFinalizer
import com.streeter.domain.work.WalkWork
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
        private val walkSyncFinalizer: WalkSyncFinalizer,
        private val workManager: WorkManager,
        private val syncFailureHandler: SyncFailureHandler,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val walkId = inputData.getLong(KEY_WALK_ID, -1L)
            if (walkId == -1L) return Result.failure()

            return remoteSyncRepository.syncWalk(walkId).fold(
                onSuccess = {
                    syncFailureHandler.onSuccess()
                    workManager.enqueueUniqueWork(
                        PullSyncWorker.UNIQUE_WORK_NAME,
                        ExistingWorkPolicy.KEEP,
                        PullSyncWorker.buildOneTimeRequest(),
                    )
                    Result.success()
                },
                onFailure = { throwable ->
                    Timber.w(throwable, "Sync failed for walk $walkId, attempt $runAttemptCount")
                    walkSyncFinalizer.fail(walkId)
                    if (syncFailureHandler.onFailure(throwable, runAttemptCount)) Result.retry() else Result.failure()
                },
            )
        }

        companion object {
            const val KEY_WALK_ID = "walk_id"

            fun buildRequest(walkId: Long): OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInputData(workDataOf(KEY_WALK_ID to walkId))
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag(WalkWork.syncName(walkId))
                    .build()
        }
    }
