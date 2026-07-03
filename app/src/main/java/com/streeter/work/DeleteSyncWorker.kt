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
import com.streeter.domain.repository.RemoteSyncRepository
import com.streeter.domain.work.WalkWork
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Dispatches a tombstoned walk's pending server delete (issue #22, ADR-0003).
 *
 * Offline-capable (requires connectivity, retries with exponential backoff). On a confirmed server
 * delete the repository hard-deletes the local row, so no `DELETED` tombstone lingers locally.
 */
@HiltWorker
class DeleteSyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val remoteSyncRepository: RemoteSyncRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val walkId = inputData.getLong(KEY_WALK_ID, -1L)
            if (walkId == -1L) return Result.failure()

            return remoteSyncRepository.deleteWalk(walkId).fold(
                onSuccess = { Result.success() },
                onFailure = { throwable ->
                    Timber.w(throwable, "Delete dispatch failed for walk $walkId, attempt $runAttemptCount")
                    if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
                },
            )
        }

        companion object {
            const val KEY_WALK_ID = "walk_id"
            private const val MAX_RETRIES = 3

            fun buildRequest(walkId: Long): OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<DeleteSyncWorker>()
                    .setInputData(workDataOf(KEY_WALK_ID to walkId))
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .addTag(WalkWork.deleteName(walkId))
                    .build()
        }
    }
