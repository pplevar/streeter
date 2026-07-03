package com.streeter.data.remote.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user-facing signal that sync was rejected for auth (HTTP 401) — the shared bearer token is
 * missing or wrong (issue #19, ADR-0002).
 *
 * Held in memory: a raised failure re-raises itself on the next sync attempt if still unresolved, so
 * there is no need to persist it across process death. Settings observes [authFailed] to show a
 * "set/check your sync token" banner, and clears it once the user edits the token.
 */
@Singleton
class SyncAuthStatus
    @Inject
    constructor() {
        private val _authFailed = MutableStateFlow(false)

        /** `true` while sync is blocked by an auth failure the user has not yet resolved. */
        val authFailed: StateFlow<Boolean> = _authFailed.asStateFlow()

        fun raiseAuthFailure() {
            _authFailed.value = true
        }

        fun clearAuthFailure() {
            _authFailed.value = false
        }
    }
