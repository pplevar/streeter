package com.streeter.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkerParameters
import com.streeter.domain.repository.RemoteSyncRepository
import com.streeter.domain.sync.SyncOperation
import com.streeter.domain.sync.SyncOutcomeHandler
import com.streeter.domain.work.WalkWork
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Pushes one walk's metadata and GPS Trace to the server. */
@HiltWorker
class SyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val remoteSyncRepository: RemoteSyncRepository,
        outcomes: SyncOutcomeHandler,
    ) : SyncOperationWorker<SyncOperation.Push>(context, params, outcomes) {
        override fun operation(): SyncOperation.Push? = inputWalkId()?.let(SyncOperation::Push)

        override suspend fun perform(operation: SyncOperation.Push): kotlin.Result<Unit> = remoteSyncRepository.syncWalk(operation.walkId)

        companion object {
            fun buildRequest(walkId: Long): OneTimeWorkRequest =
                SyncOperationWorker.buildRequest<SyncWorker>(walkId = walkId, tag = WalkWork.syncName(walkId))
        }
    }
