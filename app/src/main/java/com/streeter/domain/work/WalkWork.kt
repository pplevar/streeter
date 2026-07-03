package com.streeter.domain.work

/**
 * Single source of truth for the WorkManager unique-work names used to schedule
 * a walk's background jobs.
 *
 * Every enqueue/observe/cancel of Calculation must use [calculationName], and every
 * enqueue/observe of Sync must use [syncName]. Sharing these names is what lets a
 * single Stop reliably cancel Calculation regardless of which path started it
 * (issue #15, ADR-0001).
 */
object WalkWork {
    fun calculationName(walkId: Long): String = "match_$walkId"

    fun syncName(walkId: Long): String = "sync_$walkId"

    fun deleteName(walkId: Long): String = "delete_$walkId"
}
