package com.streeter

import com.streeter.domain.model.WalkStatus
import com.streeter.domain.recording.GpsObservation
import com.streeter.domain.recording.RecordingSession
import com.streeter.domain.work.WalkRecalculator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral spec for [RecordingSession] — what it means to record, pause and end a Recorded
 * Walk (issue #61), extracted from the foreground service so it can be driven on the JVM.
 *
 * The four rules under test: points survive a failed write, a rejected Outlier Point never
 * becomes the anchor for the next comparison, duration accumulates across repeated
 * pause/resume, and ending a walk goes through [WalkRecalculator].
 */
class RecordingSessionTest {
    private val clock = MutableClock(nowMs = 1_000L)
    private val walkRepository = FakeWalkRepository()
    private val gpsPointRepository = FakeGpsPointRepository()
    private val jobRepository = FakePendingMatchJobRepository()
    private val scheduler = FakeWalkWorkScheduler()

    private fun session(recalculatorScheduler: FakeWalkWorkScheduler = scheduler) =
        RecordingSession(
            walkRepository = walkRepository,
            gpsPointRepository = gpsPointRepository,
            pendingMatchJobRepository = jobRepository,
            walkRecalculator = WalkRecalculator(walkRepository, recalculatorScheduler),
            clock = clock,
            wakeGuard = NoopWakeGuard,
        )

    /** ~22 m per 0.0002° of latitude; at a 10 s cadence that is a plausible ~8 km/h. */
    private fun observation(
        lat: Double,
        atMs: Long,
    ) = GpsObservation(lat = lat, lng = 0.0, timestamp = atMs, accuracyM = 5f, speedKmh = 4f)

    private suspend fun RecordingSession.recordAll(vararg observations: GpsObservation) = observations.forEach { record(it) }

    @Test
    fun `a partial batch stays buffered and is written when the walk ends`() =
        runBlocking {
            val session = session()
            session.start()

            repeat(3) { i -> session.record(observation(lat = 0.0002 * i, atMs = 10_000L * i)) }
            assertTrue("a partial batch must not hit the database", gpsPointRepository.inserted.isEmpty())

            session.stop()
            assertEquals(3, gpsPointRepository.inserted.size)
        }

    @Test
    fun `a full batch is flushed once and not written again`() =
        runBlocking {
            val session = session()
            session.start()

            repeat(RecordingSession.FLUSH_BATCH_SIZE) { i ->
                session.record(observation(lat = 0.0002 * i, atMs = 10_000L * i))
            }
            assertEquals(RecordingSession.FLUSH_BATCH_SIZE, gpsPointRepository.inserted.size)

            session.stop()
            assertEquals(
                "already-durable points must not be written twice",
                RecordingSession.FLUSH_BATCH_SIZE,
                gpsPointRepository.inserted.size,
            )
        }

    @Test
    fun `points survive a failed write and are written by the next flush`() =
        runBlocking {
            val session = session()
            session.start()

            gpsPointRepository.failWrites = true
            repeat(RecordingSession.FLUSH_BATCH_SIZE) { i ->
                session.record(observation(lat = 0.0002 * i, atMs = 10_000L * i))
            }
            assertTrue("the failed write persisted nothing", gpsPointRepository.inserted.isEmpty())

            gpsPointRepository.failWrites = false
            session.stop()

            assertEquals(
                "a failed write must not discard the batch",
                RecordingSession.FLUSH_BATCH_SIZE,
                gpsPointRepository.inserted.size,
            )
        }

    @Test
    fun `a rejected Outlier Point does not become the anchor for the next comparison`() =
        runBlocking {
            val session = session()
            session.start()

            session.recordAll(
                observation(lat = 0.0000, atMs = 0L),
                observation(lat = 0.0002, atMs = 10_000L),
                // ~5.5 km in 10 s — implausible, so rejected.
                observation(lat = 0.0500, atMs = 20_000L),
                // Plausible from the last *kept* point, implausible from the spike.
                observation(lat = 0.0004, atMs = 30_000L),
            )

            val filtered = session.points.value.map { it.isFiltered }
            assertEquals(listOf(false, false, true, false), filtered)
        }

    @Test
    fun `an Outlier Point is still stored`() =
        runBlocking {
            val session = session()
            session.start()

            session.recordAll(
                observation(lat = 0.0000, atMs = 0L),
                observation(lat = 0.0500, atMs = 10_000L),
            )
            session.stop()

            assertEquals(2, gpsPointRepository.inserted.size)
            assertTrue(gpsPointRepository.inserted.any { it.isFiltered })
        }

    @Test
    fun `every recorded point belongs to the walk in progress`() =
        runBlocking {
            val session = session()
            val walkId = session.start()

            session.record(observation(lat = 0.0, atMs = 0L))
            session.stop()

            assertTrue(walkId > 0L)
            assertTrue(gpsPointRepository.inserted.all { it.walkId == walkId })
        }

    @Test
    fun `duration accumulates across repeated pause and resume`() =
        runBlocking {
            val session = session()
            val walkId = session.start()

            clock.advance(30_000L)
            session.pause()
            clock.advance(120_000L) // paused: this must not count
            session.resume(walkId)
            clock.advance(20_000L)
            session.pause()
            clock.advance(5_000L)
            session.resume(walkId)
            clock.advance(10_000L)
            session.stop()

            assertEquals(60_000L, walkRepository.getWalkById(walkId)!!.durationMs)
        }

    @Test
    fun `pausing records the segment and clears the resume mark`() =
        runBlocking {
            val session = session()
            val walkId = session.start()

            clock.advance(30_000L)
            session.pause()

            val walk = walkRepository.getWalkById(walkId)!!
            assertEquals(30_000L, walk.durationMs)
            assertEquals(null, walk.lastResumedAt)
            assertTrue(walk.isPaused)
            assertTrue(session.isPaused.value)
        }

    @Test
    fun `stopping while paused adds no further duration`() =
        runBlocking {
            val session = session()
            val walkId = session.start()

            clock.advance(30_000L)
            session.pause()
            clock.advance(60_000L)
            session.stop()

            val walk = walkRepository.getWalkById(walkId)!!
            assertEquals(30_000L, walk.durationMs)
            assertFalse(walk.isPaused)
        }

    @Test
    fun `pausing flushes the buffered points`() =
        runBlocking {
            val session = session()
            session.start()

            session.record(observation(lat = 0.0, atMs = 0L))
            session.pause()

            assertEquals(1, gpsPointRepository.inserted.size)
        }

    @Test
    fun `ending a walk goes through the recalculation module`() =
        runBlocking {
            val session = session()
            val walkId = session.start()

            session.stop()

            val walk = walkRepository.getWalkById(walkId)!!
            assertEquals(WalkStatus.PENDING_MATCH, walk.status)
            // ADR-0001: a brand-new walk needs Sync and Calculation in parallel.
            assertEquals(listOf(walkId), scheduler.newWalkProcessing)
            assertEquals(walkId, jobRepository.getJobForWalk(walkId)?.walkId)
        }

    @Test
    fun `the final duration is durable before Calculation is enqueued`() =
        runBlocking {
            // WalkRecalculator reloads the walk and writes status + updatedAt, so a duration
            // written after it ran would be dropped.
            var durationAtEnqueue: Long? = null
            val observingScheduler =
                object : FakeWalkWorkScheduler() {
                    override fun enqueueNewWalkProcessing(walkId: Long) {
                        durationAtEnqueue = runBlocking { walkRepository.getWalkById(walkId)?.durationMs }
                        super.enqueueNewWalkProcessing(walkId)
                    }
                }
            val session = session(recalculatorScheduler = observingScheduler)
            val walkId = session.start()
            clock.advance(30_000L)

            session.stop()

            assertEquals(30_000L, durationAtEnqueue)
            assertEquals(30_000L, walkRepository.getWalkById(walkId)!!.durationMs)
        }

    @Test
    fun `starting twice keeps the walk in progress`() =
        runBlocking {
            val session = session()
            val first = session.start()
            val second = session.start()

            assertEquals(first, second)
            assertEquals(1, walkRepository.walks.size)
        }

    @Test
    fun `points recorded after the walk ends are ignored`() =
        runBlocking {
            val session = session()
            session.start()
            session.stop()

            session.record(observation(lat = 0.0, atMs = 0L))

            assertTrue(gpsPointRepository.inserted.isEmpty())
            assertTrue(session.points.value.isEmpty())
        }
}
