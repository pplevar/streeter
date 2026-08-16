package com.streeter

import com.streeter.domain.sync.SyncAuthStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec for the user-facing sync auth signal (issue #19): a raised auth failure stays raised (so the
 * Settings banner persists) until it is explicitly cleared — e.g. once the user corrects the token.
 */
class SyncAuthStatusTest {
    @Test
    fun `starts clear`() =
        runBlocking {
            assertFalse(SyncAuthStatus().authFailed.first())
        }

    @Test
    fun `raising an auth failure sets the signal`() =
        runBlocking {
            val status = SyncAuthStatus()
            status.raiseAuthFailure()
            assertTrue(status.authFailed.first())
        }

    @Test
    fun `clearing the signal drops it`() =
        runBlocking {
            val status = SyncAuthStatus()
            status.raiseAuthFailure()
            status.clearAuthFailure()
            assertFalse(status.authFailed.first())
        }
}
