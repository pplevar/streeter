package com.streeter.domain.work

import com.streeter.domain.model.SyncStatus
import com.streeter.domain.repository.WalkRepository
import javax.inject.Inject

/**
 * Lands the outcome of a sync attempt on the walk, keeping the (testable) decision of
 * *what a success or failure does to the walk's sync state* out of the Android worker.
 *
 * The server id is the walk's identity on the server: a walk that has synced once must keep it
 * for the rest of its life, or the next successful sync creates a **second** server walk instead of
 * updating the existing one (issue #52). [fail] therefore only moves the status — it cannot touch
 * the server id — while [succeed] is the one path that writes it.
 */
class WalkSyncFinalizer
    @Inject
    constructor(
        private val walkRepository: WalkRepository,
    ) {
        /** Sync succeeded: record the server identity the server just confirmed. */
        suspend fun succeed(
            walkId: Long,
            serverWalkId: Long,
        ) = walkRepository.updateSyncStatus(walkId, SyncStatus.SYNCED, serverWalkId)

        /** Sync failed: land in `SYNC_FAILED`, preserving any server id the walk already earned. */
        suspend fun fail(walkId: Long) = walkRepository.markSyncFailed(walkId)
    }
