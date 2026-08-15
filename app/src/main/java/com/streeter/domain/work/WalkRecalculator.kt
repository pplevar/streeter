package com.streeter.domain.work

import com.streeter.domain.model.WalkStatus
import com.streeter.domain.repository.WalkRepository
import javax.inject.Inject

/**
 * Owns the "the GPS Trace changed, so Calculation is stale" transition (issue #53).
 *
 * Whenever a walk's Trace changes — a finished recording, a manual walk, a route or points
 * edit, a pulled remote trace, a user-requested recalculate, a recording recovered after
 * process death — exactly the same three things must happen, and never a subset:
 *
 * 1. the walk goes to `PENDING_MATCH`, so the UI shows it as processing,
 * 2. `updatedAt` is bumped, because that field is what cross-device convergence compares —
 *    skipping it can lose the local edit when another device's copy wins,
 * 3. Calculation is enqueued, so the coverage actually gets recomputed.
 *
 * Callers must persist any *other* field changes (duration, geometry, points) **before**
 * calling in: this reloads the walk and writes only status and `updatedAt`.
 */
class WalkRecalculator
    @Inject
    constructor(
        private val walkRepository: WalkRepository,
        private val scheduler: WalkWorkScheduler,
    ) {
        /**
         * Apply the transition to [walkId]. Set [newWalk] for a brand-new walk (a finished
         * recording or a freshly-saved manual walk), which additionally needs Sync to make it
         * durable on the server; Sync and Calculation then go out in parallel (ADR-0001).
         * Every other caller is recalculate-only — the walk is already durable, so an upfront
         * Sync would be redundant.
         *
         * No-op if the walk is gone.
         */
        suspend fun traceChanged(
            walkId: Long,
            newWalk: Boolean = false,
        ) {
            val walk = walkRepository.getWalkById(walkId) ?: return
            walkRepository.updateWalk(
                walk.copy(
                    status = WalkStatus.PENDING_MATCH,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            // Enqueue only after the status write lands: Calculation reads the walk when it runs.
            if (newWalk) scheduler.enqueueNewWalkProcessing(walkId) else scheduler.enqueueCalculation(walkId)
        }
    }
