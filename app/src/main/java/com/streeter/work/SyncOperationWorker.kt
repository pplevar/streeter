package com.streeter.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.streeter.domain.sync.SyncDisposition
import com.streeter.domain.sync.SyncOperation
import com.streeter.domain.sync.SyncOutcomeHandler
import com.streeter.domain.work.WorkRetryPolicy
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * The plumbing every sync worker shares (issue #60): read the operation out of the input data, run
 * it, and hand the outcome to [SyncOutcomeHandler] — which owns everything the outcome *means*.
 *
 * Subclasses supply only their operation and the call that performs it; the fold, the logging and
 * the mapping onto WorkManager's `Result` live here once.
 */
abstract class SyncOperationWorker<O : SyncOperation>(
    context: Context,
    params: WorkerParameters,
    private val outcomes: SyncOutcomeHandler,
) : CoroutineWorker(context, params) {
    /** The unit of sync this run dispatches, or `null` if the input data does not name one. */
    protected abstract fun operation(): O?

    /** Performs [operation] against the server. */
    protected abstract suspend fun perform(operation: O): kotlin.Result<Unit>

    final override suspend fun doWork(): ListenableWorker.Result {
        val operation = operation() ?: return ListenableWorker.Result.failure()

        return perform(operation).fold(
            onSuccess = { outcomes.onSuccess(operation).toWorkResult() },
            onFailure = { throwable ->
                Timber.w(throwable, "Sync failed for $operation, attempt $runAttemptCount")
                outcomes.onFailure(operation, throwable, runAttemptCount).toWorkResult()
            },
        )
    }

    /** The walk this run is about, or `null` when the input data carries no usable id. */
    protected fun inputWalkId(): Long? = inputData.getLong(KEY_WALK_ID, -1L).takeIf { it != -1L }

    private fun SyncDisposition.toWorkResult(): ListenableWorker.Result =
        when (this) {
            SyncDisposition.SUCCESS -> ListenableWorker.Result.success()
            SyncDisposition.RETRY -> ListenableWorker.Result.retry()
            SyncDisposition.FAILURE -> ListenableWorker.Result.failure()
        }

    companion object {
        const val KEY_WALK_ID = "walk_id"

        /** Sync always needs the network — a pull or push has nothing to do offline. */
        fun connectedConstraints(): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        /**
         * The one-time request shape every sync worker uses: needs connectivity, retries on the
         * shared backoff, optionally carries a walk id and a unique-work tag.
         */
        inline fun <reified W : ListenableWorker> buildRequest(
            walkId: Long? = null,
            tag: String? = null,
        ): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<W>()
                .apply {
                    if (walkId != null) setInputData(workDataOf(KEY_WALK_ID to walkId))
                    if (tag != null) addTag(tag)
                }
                .setConstraints(connectedConstraints())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRetryPolicy.BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
    }
}
