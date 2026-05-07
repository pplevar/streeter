package com.streeter.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import com.streeter.domain.repository.RemoteSyncRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

@HiltWorker
class PullSyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val remoteSyncRepository: RemoteSyncRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val prefs = applicationContext.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
            val since = prefs.getLong("last_pull_sync_at", 0L)

            return remoteSyncRepository.pullWalks(since).fold(
                onSuccess = { Result.success() },
                onFailure = { throwable ->
                    Timber.w(throwable, "Pull sync failed, attempt $runAttemptCount")
                    if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
                },
            )
        }

        companion object {
            private const val MAX_RETRIES = 3
            const val UNIQUE_WORK_NAME = "pull_sync"

            fun buildOneTimeRequest(): OneTimeWorkRequest =
                OneTimeWorkRequestBuilder<PullSyncWorker>()
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()

            fun buildPeriodicRequest(): PeriodicWorkRequest =
                PeriodicWorkRequestBuilder<PullSyncWorker>(24, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    )
                    .build()
        }
    }
