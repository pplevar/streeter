package com.streeter.domain.work

import com.streeter.domain.model.WalkStatus
import com.streeter.domain.repository.PendingMatchJobRepository
import com.streeter.domain.repository.StreetRepository
import com.streeter.domain.repository.WalkRepository
import javax.inject.Inject

/**
 * Originating-device walk deletion (issue #22, ADR-0003).
 *
 * Deletion branches on whether the walk was ever synced:
 * - **Never synced** (`serverWalkId == null`): hard-delete locally with no server call.
 * - **Synced**: mark the walk `DELETED`, strip its Coverage immediately (so the covered-street
 *   count is correct at once, online or off), and queue a pending server delete. The tombstone
 *   is dispatched by [WalkWorkScheduler.enqueueDelete] and hard-deleted once the server confirms.
 *
 * A `RECORDING` walk cannot be deleted.
 */
class WalkDeleter
    @Inject
    constructor(
        private val walkRepository: WalkRepository,
        private val streetRepository: StreetRepository,
        private val pendingMatchJobRepository: PendingMatchJobRepository,
        private val walkWorkScheduler: WalkWorkScheduler,
    ) {
        suspend fun delete(walkId: Long) {
            val walk = walkRepository.getWalkById(walkId) ?: return
            check(walk.status != WalkStatus.RECORDING) { "Cannot delete a RECORDING walk" }

            // A walk being deleted must not keep computing coverage or hold a queued match job.
            walkWorkScheduler.cancelCalculation(walkId)
            pendingMatchJobRepository.deleteJobForWalk(walkId)

            if (walk.serverWalkId == null) {
                // Never synced: no server presence — remove it locally (Room CASCADE drops Coverage).
                walkRepository.hardDeleteWalk(walkId)
            } else {
                // Synced: tombstone, strip Coverage now so counts are correct immediately, then
                // dispatch the server delete which hard-deletes the local row on 204.
                walkRepository.markWalkDeleted(walkId)
                streetRepository.deleteWalkCoverageForWalk(walkId)
                walkWorkScheduler.enqueueDelete(walkId)
            }
        }
    }
