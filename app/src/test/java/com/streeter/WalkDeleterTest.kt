package com.streeter

import com.streeter.domain.model.SyncStatus
import com.streeter.domain.model.Walk
import com.streeter.domain.model.WalkSource
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.work.WalkDeleter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral spec for [WalkDeleter] — originating-device deletion branching (issue #22, ADR-0003).
 *
 * A never-synced walk is hard-deleted locally with no server call; a synced walk is tombstoned,
 * has its Coverage stripped immediately, and gets a pending server delete queued.
 */
class WalkDeleterTest {
    private fun walk(
        id: Long = 1L,
        status: WalkStatus = WalkStatus.COMPLETED,
        serverWalkId: Long? = null,
    ) = Walk(
        id = id,
        title = null,
        date = 0L,
        durationMs = 0L,
        distanceM = 0.0,
        status = status,
        source = WalkSource.RECORDED,
        createdAt = 0L,
        updatedAt = 0L,
        syncStatus = if (serverWalkId == null) SyncStatus.PENDING_SYNC else SyncStatus.SYNCED,
        serverWalkId = serverWalkId,
    )

    private fun deleter(
        walks: FakeWalkRepository,
        streets: RecordingStreetRepository = RecordingStreetRepository(),
        jobs: FakePendingMatchJobRepository = FakePendingMatchJobRepository(),
        scheduler: FakeWalkWorkScheduler = FakeWalkWorkScheduler(),
    ) = WalkDeleter(walks, streets, jobs, scheduler)

    @Test
    fun `never-synced walk is hard-deleted locally with no server dispatch`() =
        runBlocking {
            val repo = FakeWalkRepository(listOf(walk(serverWalkId = null)))
            val scheduler = FakeWalkWorkScheduler()
            val jobs = FakePendingMatchJobRepository()

            deleter(repo, jobs = jobs, scheduler = scheduler).delete(1L)

            // Gone locally, and no pending server delete was queued.
            assertNull(repo.getWalkById(1L))
            assertTrue(scheduler.deleteEnqueued.isEmpty())
            // A deleted walk must stop computing and drop its queued match job.
            assertEquals(listOf(1L), scheduler.calculationCancelled)
        }

    @Test
    fun `synced walk is tombstoned, strips coverage now, and queues a server delete`() =
        runBlocking {
            val repo = FakeWalkRepository(listOf(walk(serverWalkId = 99L)))
            val streets = RecordingStreetRepository()
            val scheduler = FakeWalkWorkScheduler()

            deleter(repo, streets = streets, scheduler = scheduler).delete(1L)

            // Tombstoned but still present locally (kept with its serverWalkId for dispatch).
            val tombstone = repo.getWalkById(1L)
            assertEquals(WalkStatus.DELETED, tombstone?.status)
            assertEquals(99L, tombstone?.serverWalkId)
            // Coverage stripped immediately so the covered-street count drops at once, even offline.
            assertEquals(listOf(1L), streets.deletedWalkCoverages)
            // Pending server delete queued.
            assertEquals(listOf(1L), scheduler.deleteEnqueued)
        }

    @Test
    fun `a RECORDING walk cannot be deleted`() =
        runBlocking {
            val repo = FakeWalkRepository(listOf(walk(status = WalkStatus.RECORDING, serverWalkId = 99L)))
            val scheduler = FakeWalkWorkScheduler()

            assertThrows(IllegalStateException::class.java) {
                runBlocking { deleter(repo, scheduler = scheduler).delete(1L) }
            }

            // Untouched: still present, not tombstoned, nothing dispatched.
            assertEquals(WalkStatus.RECORDING, repo.getWalkById(1L)?.status)
            assertTrue(scheduler.deleteEnqueued.isEmpty())
        }
}
