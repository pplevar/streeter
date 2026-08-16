package com.streeter

import com.streeter.data.remote.auth.SyncAuthStatus
import com.streeter.domain.model.SyncStatus
import com.streeter.domain.sync.SyncAuthException
import com.streeter.domain.sync.SyncDisposition
import com.streeter.domain.sync.SyncOperation
import com.streeter.domain.sync.SyncOutcomeHandler
import com.streeter.domain.work.WalkSyncFinalizer
import com.streeter.domain.work.WorkRetryPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Spec for what a sync outcome *means* (issues #19, #60). The workers hold no part of this: the
 * status write that follows a failed push, the pull that follows a successful push, the retry
 * budget and the auth fast-fail (ADR-0004) are all decided here, on the JVM.
 */
class SyncOutcomeHandlerTest {
    private val authStatus = SyncAuthStatus()
    private val walks = FakeWalkRepository(listOf(testWalk(id = 1L, serverWalkId = 77L)))
    private val scheduler = FakeWalkWorkScheduler()
    private val handler = SyncOutcomeHandler(authStatus, WalkSyncFinalizer(walks), scheduler)

    @Test
    fun `a successful push triggers a pull`() =
        runBlocking {
            val disposition = handler.onSuccess(SyncOperation.Push(walkId = 1L))

            assertEquals(SyncDisposition.SUCCESS, disposition)
            assertEquals(1, scheduler.pullEnqueued)
        }

    @Test
    fun `a successful pull or delete does not trigger another pull`() =
        runBlocking {
            handler.onSuccess(SyncOperation.Pull)
            handler.onSuccess(SyncOperation.Delete(walkId = 1L))

            assertEquals(0, scheduler.pullEnqueued)
        }

    @Test
    fun `a failed push marks the walk sync-failed and keeps its server id`() =
        runBlocking {
            handler.onFailure(SyncOperation.Push(walkId = 1L), IOException("boom"), runAttemptCount = 0)

            val walk = walks.getWalkById(1L)!!
            assertEquals(SyncStatus.SYNC_FAILED, walk.syncStatus)
            assertEquals(77L, walk.serverWalkId)
        }

    @Test
    fun `a failed pull or delete writes no walk status`() =
        runBlocking {
            handler.onFailure(SyncOperation.Pull, IOException("boom"), runAttemptCount = 0)
            handler.onFailure(SyncOperation.Delete(walkId = 1L), IOException("boom"), runAttemptCount = 0)

            assertEquals(SyncStatus.PENDING_SYNC, walks.getWalkById(1L)!!.syncStatus)
        }

    @Test
    fun `an auth failure fails fast and raises the user-facing signal`() =
        runBlocking {
            val disposition = handler.onFailure(SyncOperation.Pull, SyncAuthException(), runAttemptCount = 0)

            assertEquals(SyncDisposition.FAILURE, disposition)
            assertTrue(authStatus.authFailed.first())
        }

    @Test
    fun `a transient failure retries and leaves the signal untouched`() =
        runBlocking {
            val disposition = handler.onFailure(SyncOperation.Pull, IOException("boom"), runAttemptCount = 0)

            assertEquals(SyncDisposition.RETRY, disposition)
            assertFalse(authStatus.authFailed.first())
        }

    @Test
    fun `a transient failure gives up once the shared attempt budget is spent`() =
        runBlocking {
            val disposition =
                handler.onFailure(SyncOperation.Pull, IOException("boom"), WorkRetryPolicy.MAX_ATTEMPTS)

            assertEquals(SyncDisposition.FAILURE, disposition)
        }

    @Test
    fun `a success clears a standing auth signal`() =
        runBlocking {
            authStatus.raiseAuthFailure()

            handler.onSuccess(SyncOperation.Pull)

            assertFalse(authStatus.authFailed.first())
        }
}
