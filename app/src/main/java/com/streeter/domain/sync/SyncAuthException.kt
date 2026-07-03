package com.streeter.domain.sync

/**
 * The server rejected a sync request's auth (HTTP 401): the shared bearer token is missing or wrong
 * (issue #19, ADR-0002).
 *
 * Unlike a transient network or server error, this is **not** fixable by retrying — only a corrected
 * token resolves it. The sync path fails fast on this (no backoff retries) and surfaces it to the
 * user so they can set or correct their token in Settings.
 */
class SyncAuthException : Exception("Sync auth rejected (HTTP 401): the sync token is missing or invalid.")
