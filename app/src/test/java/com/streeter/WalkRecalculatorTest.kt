package com.streeter

import com.streeter.domain.model.SyncStatus
import com.streeter.domain.model.Walk
import com.streeter.domain.model.WalkSource
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.work.WalkRecalculator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral spec for [WalkRecalculator] — the "the GPS Trace changed, Calculation is stale"
 * transition (issue #53).
 *
 * The rule is all-or-nothing: mark the walk PENDING_MATCH, bump `updatedAt`, and enqueue
 * Calculation. A brand-new walk additionally gets Sync, in parallel (ADR-0001).
 */
class WalkRecalculatorTest {
    private fun walk(
        id: Long = 1L,
        status: WalkStatus = WalkStatus.COMPLETED,
        updatedAt: Long = 0L,
    ) = Walk(
        id = id,
        title = null,
        date = 0L,
        durationMs = 0L,
        distanceM = 0.0,
        status = status,
        source = WalkSource.RECORDED,
        createdAt = 0L,
        updatedAt = updatedAt,
        syncStatus = SyncStatus.SYNCED,
        serverWalkId = 99L,
    )

    @Test
    fun `an existing walk is marked pending, bumped, and gets Calculation only`() =
        runBlocking {
            val repo = FakeWalkRepository(listOf(walk()))
            val scheduler = FakeWalkWorkScheduler()

            WalkRecalculator(repo, scheduler).traceChanged(1L)

            val updated = repo.getWalkById(1L)!!
            assertEquals(WalkStatus.PENDING_MATCH, updated.status)
            assertTrue("updatedAt must be bumped", updated.updatedAt > 0L)
            assertEquals(listOf(1L), scheduler.calculationEnqueued)
            // Recalculate-only: no upfront Sync — the walk is already durable.
            assertTrue(scheduler.syncEnqueued.isEmpty())
            assertTrue(scheduler.newWalkProcessing.isEmpty())
        }

    @Test
    fun `a new walk is marked pending, bumped, and gets Sync and Calculation in parallel`() =
        runBlocking {
            val repo = FakeWalkRepository(listOf(walk(status = WalkStatus.RECORDING)))
            val scheduler = FakeWalkWorkScheduler()

            WalkRecalculator(repo, scheduler).traceChanged(1L, newWalk = true)

            val updated = repo.getWalkById(1L)!!
            assertEquals(WalkStatus.PENDING_MATCH, updated.status)
            assertTrue("updatedAt must be bumped", updated.updatedAt > 0L)
            // ADR-0001: Sync (durability) and Calculation (coverage) go out together.
            assertEquals(listOf(1L), scheduler.newWalkProcessing)
            assertTrue(scheduler.calculationEnqueued.isEmpty())
        }

    @Test
    fun `an unknown walk enqueues nothing`() =
        runBlocking {
            val repo = FakeWalkRepository(emptyList())
            val scheduler = FakeWalkWorkScheduler()

            WalkRecalculator(repo, scheduler).traceChanged(42L)

            assertTrue(scheduler.calculationEnqueued.isEmpty())
            assertTrue(scheduler.newWalkProcessing.isEmpty())
        }

    @Test
    fun `the status write lands before Calculation is enqueued`() =
        runBlocking {
            // The worker reads the walk's status when it runs; enqueueing before the write
            // would let it observe the stale status.
            val repo = FakeWalkRepository(listOf(walk()))
            var statusAtEnqueue: WalkStatus? = null
            val scheduler =
                object : FakeWalkWorkScheduler() {
                    override fun enqueueCalculation(walkId: Long) {
                        statusAtEnqueue = runBlocking { repo.getWalkById(walkId)?.status }
                        super.enqueueCalculation(walkId)
                    }
                }

            WalkRecalculator(repo, scheduler).traceChanged(1L)

            assertEquals(WalkStatus.PENDING_MATCH, statusAtEnqueue)
        }
}
