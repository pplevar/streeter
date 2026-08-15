package com.streeter

import com.streeter.domain.model.SyncStatus
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
        syncStatus: SyncStatus = SyncStatus.PENDING_SYNC,
        serverWalkId: Long? = null,
    ) = testWalk(syncStatus = syncStatus, serverWalkId = serverWalkId)

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
            val server = FakeServer()

            // A sync sends the walk's current server id (null the first time) and adopts whatever
            // the server answers with — exactly what RemoteSyncRepositoryImpl does.
            suspend fun sync() = finalizer.succeed(1L, server.upsert(repo.getWalkById(1L)!!.serverWalkId))

            sync()
            finalizer.fail(1L)
            sync()

            assertEquals(SyncStatus.SYNCED, repo.getWalkById(1L)!!.syncStatus)
            // One server walk, not two: the failure did not cost the walk its identity.
            assertEquals(1, server.walkCount)
            assertEquals(99L, repo.getWalkById(1L)!!.serverWalkId)
        }

    /** Stands in for the server's upsert: a known id updates in place, a null id creates a walk. */
    private class FakeServer {
        var walkCount = 0
            private set

        fun upsert(serverWalkId: Long?): Long = serverWalkId ?: (99L + walkCount).also { walkCount++ }
    }
}
