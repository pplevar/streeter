package com.streeter.domain.recording

/**
 * Keeps the device awake for the duration of a block of work.
 *
 * The recording session needs its GPS batch writes to finish even if the screen goes off
 * mid-flush; holding a wake lock is an Android concern, so the session states the requirement
 * and the platform adapter satisfies it.
 */
interface WakeGuard {
    suspend fun <T> whileAwake(block: suspend () -> T): T
}
