package com.streeter

import com.streeter.domain.model.SyncStatus
import com.streeter.domain.model.Walk
import com.streeter.domain.model.WalkSource
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.work.WalkSyncFinalizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Behavioral spec for [WalkSyncFinalizer] — how a sync outcome lands on the walk (issue #52).
 *
 * The hazard: a failed attempt used to write a null server id, so the next successful sync
 * looked like a brand new walk and the server created a duplicate.
 */
class WalkSyncFinalizerTest {
    private fun walk(
        id: Long = 1L,
        syncStatus: SyncStatus = SyncStatus.PENDING_SYNC,
        serverWalkId: Long? = null,
    ) = Walk(
        id = id,
        title = null,
        date = 0L,
        durationMs = 0L,
        distanceM = 0.0,
        status = WalkStatus.COMPLETED,
        source = WalkSource.RECORDED,
        createdAt = 0L,
        updatedAt = 0L,
        syncStatus = syncStatus,
        serverWalkId = serverWalkId,
    )

    @Test
    fun `a failed attempt lands the walk in SYNC_FAILED`() =
        runBlocking {
            val repo = FakeWalkRepository(listOf(walk()))
            val finalizer = WalkSyncFinalizer(repo)

            finalizer.fail(1L)

            assertEquals(SyncStatus.SYNC_FAILED, repo.getWalkById(1L)!!.syncStatus)
        }

    @Test
    fun `a failed attempt keeps the server id of an already-synced walk`() =
        runBlocking {
            val repo = FakeWalkRepository(listOf(walk(syncStatus = SyncStatus.SYNCED, serverWalkId = 99L)))
            val finalizer = WalkSyncFinalizer(repo)

            finalizer.fail(1L)

            assertEquals(SyncStatus.SYNC_FAILED, repo.getWalkById(1L)!!.syncStatus)
            assertEquals(99L, repo.getWalkById(1L)!!.serverWalkId)
        }

    @Test
    fun `a walk that never synced is unaffected by a failure`() =
        runBlocking {
            val repo = FakeWalkRepository(listOf(walk()))
            val finalizer = WalkSyncFinalizer(repo)

            finalizer.fail(1L)

            assertNull(repo.getWalkById(1L)!!.serverWalkId)
        }

    @Test
    fun `after a failure the next success updates the existing server record`() =
        runBlocking {
            val repo = FakeWalkRepository(listOf(walk()))
            val finalizer = WalkSyncFinalizer(repo)

            // First sync succeeds and the server hands back an id.
            finalizer.succeed(1L, serverWalkId = 99L)
            // A transient failure follows.
            finalizer.fail(1L)

            // The retry still carries the server id, so the server updates walk 99 instead of
            // creating a second one.
            assertEquals(99L, repo.getWalkById(1L)!!.serverWalkId)

            finalizer.succeed(1L, serverWalkId = 99L)

            assertEquals(SyncStatus.SYNCED, repo.getWalkById(1L)!!.syncStatus)
            assertEquals(99L, repo.getWalkById(1L)!!.serverWalkId)
        }
}
