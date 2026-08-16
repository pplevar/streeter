package com.streeter.work

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.streeter.domain.work.WalkWork
import com.streeter.domain.work.WalkWorkScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WorkManager-backed [WalkWorkScheduler]. The single place that knows how a walk's
 * Calculation and Sync map onto WorkManager unique work (names from [WalkWork]).
 *
 * Calculation is enqueued with no network constraint (offline-capable); Sync carries
 * its network constraint via [SyncWorker.buildRequest]. Both use REPLACE so the latest
 * DB state coalesces — each worker reads current walk state when it runs.
 *
 * Pull is device-wide rather than per-walk, so it has one unique name every trigger shares
 * (issue #60).
 */
@Singleton
class WalkWorkSchedulerImpl
    @Inject
    constructor(
        private val workManager: WorkManager,
    ) : WalkWorkScheduler {
        override fun enqueueNewWalkProcessing(walkId: Long) {
            // Sync and Calculation run in parallel; Sync never waits for Calculation.
            enqueueSync(walkId)
            enqueueCalculation(walkId)
        }

        override fun enqueueCalculation(walkId: Long) {
            workManager.enqueueUniqueWork(
                WalkWork.calculationName(walkId),
                ExistingWorkPolicy.REPLACE,
                MapMatchingWorker.buildRequest(walkId),
            )
        }

        override fun cancelCalculation(walkId: Long) {
            workManager.cancelUniqueWork(WalkWork.calculationName(walkId))
        }

        override fun enqueueSync(walkId: Long) {
            workManager.enqueueUniqueWork(
                WalkWork.syncName(walkId),
                ExistingWorkPolicy.REPLACE,
                SyncWorker.buildRequest(walkId),
            )
        }

        override fun enqueueDelete(walkId: Long) {
            // KEEP: deletion is terminal and idempotent, so an already-queued delete must not have
            // its retry backoff reset by a repeat trigger.
            workManager.enqueueUniqueWork(
                WalkWork.deleteName(walkId),
                ExistingWorkPolicy.KEEP,
                DeleteSyncWorker.buildRequest(walkId),
            )
        }

        override fun enqueuePull() {
            // KEEP: a pull takes no arguments — a queued one will collect exactly what a fresh one
            // would — so REPLACE would only cancel work in flight and reset its retry backoff.
            // Every trigger (foreground, post-push, pull-to-refresh) shares this one policy.
            workManager.enqueueUniqueWork(
                PullSyncWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                PullSyncWorker.buildOneTimeRequest(),
            )
        }

        override fun schedulePeriodicPull() {
            workManager.enqueueUniquePeriodicWork(
                PullSyncWorker.PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PullSyncWorker.buildPeriodicRequest(),
            )
        }
    }
