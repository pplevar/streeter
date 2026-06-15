package com.streeter

import com.streeter.domain.model.JobStatus
import com.streeter.domain.model.PendingMatchJob
import com.streeter.domain.model.SyncStatus
import com.streeter.domain.model.Walk
import com.streeter.domain.model.WalkSource
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.work.WalkCalculationFinalizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral spec for [WalkCalculationFinalizer] — the decoupling of Sync from
 * Calculation (issue #15, ADR-0001).
 */
class WalkCalculationFinalizerTest {
    private fun walk(
        id: Long = 1L,
        status: WalkStatus = WalkStatus.PENDING_MATCH,
        syncStatus: SyncStatus = SyncStatus.PENDING_SYNC,
        serverWalkId: Long? = null,
        distanceM: Double = 0.0,
        source: WalkSource = WalkSource.RECORDED,
    ) = Walk(
        id = id,
        title = null,
        date = 0L,
        durationMs = 0L,
        distanceM = distanceM,
        status = status,
        source = source,
        createdAt = 0L,
        updatedAt = 0L,
        syncStatus = syncStatus,
        serverWalkId = serverWalkId,
    )

    @Test
    fun `complete lands the walk in COMPLETED with the matched distance`() =
        runBlocking {
            val repo = FakeWalkRepository(listOf(walk(distanceM = 10.0)))
            val finalizer = WalkCalculationFinalizer(repo, FakePendingMatchJobRepository(), FakeWalkWorkScheduler())

            finalizer.complete(1L, distanceM = 4321.0)

            val updated = repo.getWalkById(1L)!!
            assertEquals(WalkStatus.COMPLETED, updated.status)
            assertEquals(4321.0, updated.distanceM, 0.0)
        }

    @Test
    fun `complete keeps the existing distance when none is supplied`() =
        runBlocking {
            val repo = FakeWalkRepository(listOf(walk(distanceM = 555.0)))
            val finalizer = WalkCalculationFinalizer(repo, FakePendingMatchJobRepository(), FakeWalkWorkScheduler())

            finalizer.complete(1L)

            assertEquals(555.0, repo.getWalkById(1L)!!.distanceM, 0.0)
        }

    @Test
    fun `complete re-syncs an already-synced walk without wiping its server id`() =
        runBlocking {
            val repo = FakeWalkRepository(listOf(walk(serverWalkId = 99L, syncStatus = SyncStatus.SYNCED)))
            val scheduler = FakeWalkWorkScheduler()
            val finalizer = WalkCalculationFinalizer(repo, FakePendingMatchJobRepository(), scheduler)

            finalizer.complete(1L, distanceM = 1234.0)

            // A second Sync fires so the matched distance + COMPLETED status reach the server.
            assertEquals(listOf(1L), scheduler.syncEnqueued)
            // The server id must survive so the re-sync updates the existing walk.
            assertEquals(99L, repo.getWalkById(1L)?.serverWalkId)
        }

    @Test
    fun `stop cancels Calculation, completes without coverage, and marks the job DONE`() =
        runBlocking {
            val repo = FakeWalkRepository(listOf(walk(status = WalkStatus.PENDING_MATCH)))
            val jobs =
                FakePendingMatchJobRepository(
                    listOf(
                        PendingMatchJob(
                            id = 7L,
                            walkId = 1L,
                            queuedAt = 0L,
                            status = JobStatus.IN_PROGRESS,
                            retryCount = 0,
                            lastError = null,
                        ),
                    ),
                )
            val scheduler = FakeWalkWorkScheduler()
            val finalizer = WalkCalculationFinalizer(repo, jobs, scheduler)

            finalizer.stop(1L)

            assertEquals(listOf(1L), scheduler.calculationCancelled)
            assertEquals(WalkStatus.COMPLETED, repo.getWalkById(1L)!!.status)
            assertEquals(JobStatus.DONE, jobs.getJobForWalk(1L)!!.status)
        }

    @Test
    fun `stop leaves Sync untouched`() =
        runBlocking {
            val repo = FakeWalkRepository(listOf(walk(serverWalkId = 99L, syncStatus = SyncStatus.SYNCED)))
            val scheduler = FakeWalkWorkScheduler()
            val finalizer = WalkCalculationFinalizer(repo, FakePendingMatchJobRepository(), scheduler)

            finalizer.stop(1L)

            // Stopping affects Calculation only — no Sync is enqueued and the sync state is preserved.
            assertTrue(scheduler.syncEnqueued.isEmpty())
            assertEquals(SyncStatus.SYNCED, repo.getWalkById(1L)!!.syncStatus)
            assertEquals(99L, repo.getWalkById(1L)!!.serverWalkId)
        }
}
