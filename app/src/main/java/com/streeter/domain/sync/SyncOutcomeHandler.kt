package com.streeter.domain.sync

import com.streeter.domain.work.WalkSyncFinalizer
import com.streeter.domain.work.WalkWorkScheduler
import com.streeter.domain.work.WorkRetryPolicy
import javax.inject.Inject
import javax.inject.Singleton

/** The three things Sync dispatches, and the only vocabulary [SyncOutcomeHandler] reacts to. */
sealed interface SyncOperation {
    /** Push one walk's metadata and GPS Trace to the server. */
    data class Push(val walkId: Long) : SyncOperation

    /** Pull every walk the server has seen since this device's cursor. */
    data object Pull : SyncOperation

    /** Dispatch a tombstoned walk's deletion to the server. */
    data class Delete(val walkId: Long) : SyncOperation
}

/** What the worker must report back to WorkManager once the outcome has been folded. */
enum class SyncDisposition { SUCCESS, RETRY, FAILURE }

/**
 * What a sync outcome *means* (issues #19, #60).
 *
 * Everything a sync attempt implies beyond the network call itself is decided here, so the workers
 * are left with WorkManager plumbing only: the walk status write that follows a failed push, the
 * pull that a successful push triggers, the shared retry budget, and the auth fast-fail.
 *
 * A [SyncAuthException] (HTTP 401) is non-transient: retrying with backoff cannot fix a missing or
 * invalid token, so it fails fast and raises the user-facing [SyncAuthStatus] so Settings can
 * prompt the user (ADR-0004). Any other failure retries until [WorkRetryPolicy]'s budget is spent.
 * A success clears any standing auth signal.
 *
 * The pull cursor is deliberately *not* here: it advances page by page inside the pull itself,
 * behind `SyncCursor` (issue #54), so a partially-applied feed still resumes from the right place.
 */
@Singleton
class SyncOutcomeHandler
    @Inject
    constructor(
        private val syncAuthStatus: SyncAuthStatus,
        private val walkSyncFinalizer: WalkSyncFinalizer,
        private val workScheduler: WalkWorkScheduler,
    ) {
        /**
         * Reacts to a completed [operation]. A successful push means the server accepted local
         * changes, which is exactly the moment other devices' changes are worth collecting — so it
         * triggers a pull.
         */
        suspend fun onSuccess(operation: SyncOperation): SyncDisposition {
            syncAuthStatus.clearAuthFailure()
            if (operation is SyncOperation.Push) workScheduler.enqueuePull()
            return SyncDisposition.SUCCESS
        }

        /**
         * Reacts to a failed [operation]: lands the failure on the walk (push only — a pull or a
         * delete has no walk status to move) and decides whether the worker retries.
         */
        suspend fun onFailure(
            operation: SyncOperation,
            cause: Throwable,
            runAttemptCount: Int,
        ): SyncDisposition {
            if (operation is SyncOperation.Push) walkSyncFinalizer.fail(operation.walkId)
            if (cause is SyncAuthException) {
                syncAuthStatus.raiseAuthFailure()
                return SyncDisposition.FAILURE
            }
            return if (WorkRetryPolicy.hasAttemptsLeft(runAttemptCount)) {
                SyncDisposition.RETRY
            } else {
                SyncDisposition.FAILURE
            }
        }
    }
