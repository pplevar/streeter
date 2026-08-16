package com.streeter.domain.work

/**
 * The retry budget and backoff every retrying background job shares — Sync (push, pull, delete)
 * and Calculation alike (issue #60).
 *
 * Declared once, in seconds, so changing how patiently the app retries is a single edit rather
 * than one per worker. Android-free: the workers convert [BACKOFF_SECONDS] into whatever unit
 * WorkManager wants.
 */
object WorkRetryPolicy {
    /**
     * Retries a job gets after its first run — so four runs in total. WorkManager counts the first
     * run as attempt 0, which is what [hasAttemptsLeft] compares against.
     */
    const val MAX_RETRIES = 3

    /** Delay before the first retry; WorkManager doubles it on each subsequent attempt. */
    const val BACKOFF_SECONDS = 30L

    /** Whether a job that has just failed attempt [runAttemptCount] still has budget left. */
    fun hasAttemptsLeft(runAttemptCount: Int): Boolean = runAttemptCount < MAX_RETRIES
}
