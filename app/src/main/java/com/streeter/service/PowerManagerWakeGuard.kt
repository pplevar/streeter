package com.streeter.service

import android.content.Context
import android.os.PowerManager
import com.streeter.domain.recording.WakeGuard
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Holds a partial wake lock for the duration of the guarded work, so a batch of GPS points
 * finishes being written even if the device tries to doze mid-flush.
 */
class PowerManagerWakeGuard
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : WakeGuard {
        override suspend fun <T> whileAwake(block: suspend () -> T): T {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock =
                powerManager
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                    .apply { acquire(TIMEOUT_MS) }
            try {
                return block()
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }

        private companion object {
            const val WAKE_LOCK_TAG = "streeter:flush"

            /** Safety net: the lock is released on the normal path, this bounds the abnormal one. */
            const val TIMEOUT_MS = 5_000L
        }
    }
