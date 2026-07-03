package com.streeter

import com.streeter.data.remote.auth.SyncAuthStatus
import com.streeter.domain.sync.SyncAuthException
import com.streeter.work.SyncFailureHandler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Spec for how the sync workers react to a failure (issue #19). An auth failure (401) is terminal:
 * it never retries (backoff cannot fix a bad token) and it raises the user-facing signal. A
 * transient failure retries until the attempt budget is spent and leaves the signal untouched. A
 * success clears any standing auth signal.
 */
class SyncFailureHandlerTest {
    private fun handler(status: SyncAuthStatus = SyncAuthStatus()) = SyncFailureHandler(status) to status

    @Test
    fun `an auth failure does not retry and raises the signal`() =
        runBlocking {
            val (handler, status) = handler()

            val retry = handler.onFailure(SyncAuthException(), runAttemptCount = 0)

            assertFalse(retry)
            assertTrue(status.authFailed.first())
        }

    @Test
    fun `a transient failure retries and leaves the signal untouched`() =
        runBlocking {
            val (handler, status) = handler()

            val retry = handler.onFailure(IOException("boom"), runAttemptCount = 0)

            assertTrue(retry)
            assertFalse(status.authFailed.first())
        }

    @Test
    fun `a transient failure gives up once the attempt budget is spent`() {
        val (handler, _) = handler()

        assertFalse(handler.onFailure(IOException("boom"), runAttemptCount = SyncFailureHandler.MAX_RETRIES))
    }

    @Test
    fun `a success clears a standing auth signal`() =
        runBlocking {
            val status = SyncAuthStatus().apply { raiseAuthFailure() }
            val (handler, _) = handler(status)

            handler.onSuccess()

            assertFalse(status.authFailed.first())
        }
}
