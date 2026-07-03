package com.streeter.domain.work

/**
 * Schedules a walk's background work (Sync and Calculation) behind a small,
 * Android-free interface so the orchestration logic stays unit-testable and so
 * every call site uses the same unique-work names (see [WalkWork]).
 *
 * **Sync** pushes walk metadata + GPS Trace to the server (offline-blocked).
 * **Calculation** is the local, offline-capable map-matching + coverage step.
 * The two are decoupled: Sync never waits for Calculation (ADR-0001).
 */
interface WalkWorkScheduler {
    /**
     * A brand-new walk (a finished recording or a freshly-created manual walk):
     * enqueue Sync and Calculation **in parallel**. Sync makes the walk durable on
     * the server immediately; Calculation runs independently and offline-capable.
     */
    fun enqueueNewWalkProcessing(walkId: Long)

    /**
     * Enqueue Calculation only, replacing any existing instance. Used by paths that
     * must recompute coverage without an upfront Sync (route edit, recalculate,
     * detail-screen resume, remote pull).
     */
    fun enqueueCalculation(walkId: Long)

    /** Cancel a running/queued Calculation for this walk. Does not touch Sync. */
    fun cancelCalculation(walkId: Long)

    /** Enqueue Sync only, coalescing to the latest persisted walk state. */
    fun enqueueSync(walkId: Long)

    /**
     * Dispatch a pending server delete for a tombstoned walk (offline-capable, retrying). Calls
     * `DELETE /walks/{serverWalkId}` and hard-deletes the local row once the server confirms.
     */
    fun enqueueDelete(walkId: Long)
}
