package com.streeter.work

import com.streeter.data.remote.auth.SyncAuthStatus
import com.streeter.domain.sync.SyncAuthException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared reaction to a sync outcome for the sync workers (issue #19).
 *
 * A [SyncAuthException] (HTTP 401) is a non-transient auth failure: retrying with backoff cannot fix
 * a missing or invalid token, so it fails fast and raises the user-facing [SyncAuthStatus] so
 * Settings can prompt the user to fix their token. Any other failure retries until the attempt
 * budget is spent. A success clears any standing auth signal.
 */
@Singleton
class SyncFailureHandler
    @Inject
    constructor(
        private val syncAuthStatus: SyncAuthStatus,
    ) {
        /**
         * Reacts to a failed sync attempt and returns whether the worker should retry
         * (`Result.retry()`) rather than give up (`Result.failure()`).
         */
        fun onFailure(
            cause: Throwable,
            runAttemptCount: Int,
        ): Boolean {
            if (cause is SyncAuthException) {
                syncAuthStatus.raiseAuthFailure()
                return false
            }
            return runAttemptCount < MAX_RETRIES
        }

        /** Reacts to a successful sync: auth is currently good, so drop any standing failure signal. */
        fun onSuccess() {
            syncAuthStatus.clearAuthFailure()
        }

        companion object {
            const val MAX_RETRIES = 3
        }
    }
