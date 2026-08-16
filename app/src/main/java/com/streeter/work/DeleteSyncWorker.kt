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
        outcomes: SyncOutcomeHandler,
    ) : SyncOperationWorker<SyncOperation.Delete>(context, params, outcomes) {
        override fun operation(): SyncOperation.Delete? = inputWalkId()?.let(SyncOperation::Delete)

        override suspend fun perform(operation: SyncOperation.Delete): kotlin.Result<Unit> =
            remoteSyncRepository.deleteWalk(
                operation.walkId,
            )

        companion object {
            fun buildRequest(walkId: Long): OneTimeWorkRequest =
                SyncOperationWorker.buildRequest<DeleteSyncWorker>(walkId = walkId, tag = WalkWork.deleteName(walkId))
        }
    }
