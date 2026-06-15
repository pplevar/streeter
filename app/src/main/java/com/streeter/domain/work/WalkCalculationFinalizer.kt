package com.streeter.domain.work

import com.streeter.domain.model.JobStatus
import com.streeter.domain.model.SyncStatus
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.repository.PendingMatchJobRepository
import com.streeter.domain.repository.WalkRepository
import javax.inject.Inject

/**
 * Finalizes a walk's Calculation lifecycle, keeping the (testable) decision of
 * *when and how* to re-sync separate from the Android worker that runs Calculation.
 *
 * See ADR-0001: Calculation's only server-relevant output is the matched distance,
 * so [complete] re-syncs unconditionally; Stopping ([stop]) lands the walk in
 * COMPLETED without coverage and leaves Sync untouched.
 */
class WalkCalculationFinalizer
    @Inject
    constructor(
        private val walkRepository: WalkRepository,
        private val pendingMatchJobRepository: PendingMatchJobRepository,
        private val scheduler: WalkWorkScheduler,
    ) {
        /**
         * Calculation finished for [walkId]: mark it COMPLETED with the matched
         * [distanceM] (falling back to the walk's existing distance) and re-sync so the
         * server receives the matched distance + COMPLETED status.
         */
        suspend fun complete(
            walkId: Long,
            distanceM: Double? = null,
        ) {
            val walk = walkRepository.getWalkById(walkId) ?: return
            walkRepository.updateWalk(
                walk.copy(
                    status = WalkStatus.COMPLETED,
                    distanceM = distanceM ?: walk.distanceM,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            // Re-sync unconditionally so the matched distance + COMPLETED status reach the
            // server, even for an already-synced walk. Preserve serverWalkId so the sync
            // updates the existing server record rather than creating a duplicate.
            walkRepository.updateSyncStatus(walkId, SyncStatus.PENDING_SYNC, walk.serverWalkId)
            scheduler.enqueueSync(walkId)
        }

        /**
         * The user stopped a running Calculation: cancel it, land the walk in COMPLETED
         * without coverage, and mark its pending job DONE. Sync is left untouched.
         */
        suspend fun stop(walkId: Long) {
            scheduler.cancelCalculation(walkId)
            walkRepository.getWalkById(walkId)?.let { walk ->
                walkRepository.updateWalk(
                    walk.copy(
                        status = WalkStatus.COMPLETED,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
            pendingMatchJobRepository.getJobForWalk(walkId)?.let {
                pendingMatchJobRepository.updateJob(it.copy(status = JobStatus.DONE))
            }
        }
    }
