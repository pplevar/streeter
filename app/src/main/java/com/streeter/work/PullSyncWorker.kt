package com.streeter.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import com.streeter.domain.repository.RemoteSyncRepository
import com.streeter.domain.sync.SyncOperation
import com.streeter.domain.sync.SyncOutcomeHandler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Collects every walk the server has seen since this device's pull cursor. */
@HiltWorker
class PullSyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val remoteSyncRepository: RemoteSyncRepository,
        outcomes: SyncOutcomeHandler,
    ) : SyncOperationWorker(context, params, outcomes) {
        override fun operation(): SyncOperation = SyncOperation.Pull

        override suspend fun perform(operation: SyncOperation): kotlin.Result<Unit> = remoteSyncRepository.pullWalks()

        companion object {
            const val UNIQUE_WORK_NAME = "pull_sync"
            const val PERIODIC_WORK_NAME = "pull_sync_periodic"

            fun buildOneTimeRequest(): OneTimeWorkRequest = SyncOperationWorker.buildRequest<PullSyncWorker>()

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
