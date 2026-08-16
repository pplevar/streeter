package com.streeter

import com.streeter.domain.calculation.CalculationDisposition
import com.streeter.domain.calculation.CalculationDriver
import com.streeter.domain.calculation.CalculationSession
import com.streeter.domain.engine.CoverageEngine
import com.streeter.domain.engine.RoutingEngine
import com.streeter.domain.geometry.TraceGeometry
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.model.JobStatus
import com.streeter.domain.model.LatLng
import com.streeter.domain.model.MatchResult
import com.streeter.domain.model.PendingMatchJob
import com.streeter.domain.model.RouteResult
import com.streeter.domain.model.RouteSegment
import com.streeter.domain.model.Walk
import com.streeter.domain.model.WalkSource
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.repository.RouteSegmentRepository
import com.streeter.domain.work.WalkCalculationFinalizer
import com.streeter.domain.work.WorkRetryPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException

/**
 * Behavioral spec for [CalculationSession] — everything Calculation decides once the worker has
 * handed it a walk id (issue #62).
 *
 * The shape under test: recorded walks are map-matched, manual walks reuse the route editor's
 * segments, every dead end still completes the walk without Coverage, a walk deleted mid-run
 * writes nothing, and transient failures spend the shared retry budget.
 */
class CalculationSessionTest {
    // --- Fixtures -------------------------------------------------------------------------

    private fun walk(
        id: Long = 1L,
        status: WalkStatus = WalkStatus.PENDING_MATCH,
        source: WalkSource = WalkSource.RECORDED,
        distanceM: Double = 0.0,
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
    )

    private fun point(
        walkId: Long = 1L,
        lat: Double = 52.0,
        lng: Double = 13.0,
    ) = GpsPoint(walkId = walkId, lat = lat, lng = lng, timestamp = 0L, accuracyM = 5f, speedKmh = 4f, isFiltered = false)

    private fun job(walkId: Long = 1L) =
        PendingMatchJob(id = 1L, walkId = walkId, queuedAt = 0L, status = JobStatus.QUEUED, retryCount = 0, lastError = null)

    private fun lineString(vararg coords: Pair<Double, Double>) =
        TraceGeometry.lineStringFeature(coords.map { (lat, lng) -> LatLng(lat, lng) })

    /** A driver that records what the session published and never reports a stop. */
    private open class RecordingDriver : CalculationDriver {
        val steps = mutableListOf<Pair<Int, String>>()

        override suspend fun report(
            percent: Int,
            step: String,
        ) {
            steps += percent to step
        }
    }

    /** Records every coverage computation; optionally throws to model an engine failure. */
    private class FakeCoverageEngine(
        private val failWith: Throwable? = null,
    ) : CoverageEngine {
        val computedFor = mutableListOf<Pair<Long, List<Long>>>()

        override suspend fun computeAndPersistCoverage(
            walkId: Long,
            matchedWayIds: List<Long>,
            onProgress: (suspend (processed: Int, total: Int) -> Unit)?,
        ) {
            failWith?.let { throw it }
            computedFor += walkId to matchedWayIds
            onProgress?.invoke(1, 2)
        }
    }

    /** In-memory [RouteSegmentRepository] — the manual walk's ways, and the recorded walk's output. */
    private class FakeRouteSegmentRepository(
        segments: List<RouteSegment> = emptyList(),
    ) : RouteSegmentRepository {
        val stored = segments.toMutableList()
        val deletedFor = mutableListOf<Long>()

        override suspend fun insertSegment(segment: RouteSegment): Long {
            stored += segment
            return stored.size.toLong()
        }

        override suspend fun getSegmentsForWalk(walkId: Long): List<RouteSegment> = stored.filter { it.walkId == walkId }

        override suspend fun deleteSegmentsForWalk(walkId: Long) {
            deletedFor += walkId
            stored.removeAll { it.walkId == walkId }
        }
    }

    /** A [RoutingEngine] whose only interesting behaviour is what map matching returns. */
    private class MatchingEngine(
        private val result: Result<MatchResult>,
        private val ready: Boolean = true,
    ) : RoutingEngine {
        var initializeCount = 0

        override suspend fun isReady() = ready

        override suspend fun initialize() {
            initializeCount++
        }

        override suspend fun matchTrace(points: List<GpsPoint>): Result<MatchResult> = result

        override suspend fun route(
            from: LatLng,
            to: LatLng,
            via: List<LatLng>,
        ): Result<RouteResult> = Result.failure(UnsupportedOperationException("fake"))

        override fun getStreetName(edgeId: Long): String? = null

        override fun findNearestNamedStreet(edgeId: Long): String? = null

        override fun getEdgeLength(edgeId: Long): Double? = null

        override fun getStreetTotalLength(streetName: String): Double? = null

        override fun getEdgeGeometry(edgeId: Long): String? = null

        override fun getEdgeGeometriesForStreet(streetName: String): List<String> = emptyList()
    }

    private class Fixture(
        val walks: FakeWalkRepository,
        val points: FakeGpsPointRepository = FakeGpsPointRepository(),
        val segments: FakeRouteSegmentRepository = FakeRouteSegmentRepository(),
        val jobs: FakePendingMatchJobRepository,
        routing: RoutingEngine,
        val coverage: FakeCoverageEngine = FakeCoverageEngine(),
        val scheduler: FakeWalkWorkScheduler = FakeWalkWorkScheduler(),
    ) {
        val session =
            CalculationSession(
                walkRepository = walks,
                gpsPointRepository = points,
                routeSegmentRepository = segments,
                pendingMatchJobRepository = jobs,
                routingEngine = routing,
                coverageEngine = coverage,
                finalizer = WalkCalculationFinalizer(walks, jobs, scheduler),
            )
    }

    private fun fixture(
        walk: Walk = walk(),
        matchResult: Result<MatchResult> =
            Result.success(
                MatchResult(emptyList(), listOf(10L, 11L), lineString(52.0 to 13.0, 52.001 to 13.0), 123.0),
            ),
        routing: RoutingEngine? = null,
        coverage: FakeCoverageEngine = FakeCoverageEngine(),
        segments: FakeRouteSegmentRepository = FakeRouteSegmentRepository(),
        points: List<GpsPoint> = listOf(point(), point(lat = 52.001)),
    ): Fixture {
        val pointRepo = FakeGpsPointRepository().apply { inserted += points }
        return Fixture(
            walks = FakeWalkRepository(listOf(walk)),
            points = pointRepo,
            segments = segments,
            jobs = FakePendingMatchJobRepository(listOf(job(walk.id))),
            routing = routing ?: MatchingEngine(matchResult),
            coverage = coverage,
        )
    }

    // --- Recorded vs manual dispatch -------------------------------------------------------

    @Test
    fun `a recorded walk is map-matched, and its matched ways and distance land on the walk`() =
        runBlocking {
            val f = fixture()

            val disposition = f.session.calculate(1L, runAttemptCount = 0, driver = RecordingDriver())

            assertEquals(CalculationDisposition.SUCCESS, disposition)
            assertEquals(listOf(1L to listOf(10L, 11L)), f.coverage.computedFor)
            val updated = f.walks.getWalkById(1L)!!
            assertEquals(WalkStatus.COMPLETED, updated.status)
            assertEquals(123.0, updated.distanceM, 0.001)
            assertEquals(JobStatus.DONE, f.jobs.getJobForWalk(1L)!!.status)
        }

    @Test
    fun `the matched route replaces any route a previous run left behind`() =
        runBlocking {
            val stale = RouteSegment(id = 7L, walkId = 1L, geometryJson = lineString(1.0 to 1.0), matchedWayIds = "[99]", segmentOrder = 0)
            val f = fixture(segments = FakeRouteSegmentRepository(listOf(stale)))

            f.session.calculate(1L, runAttemptCount = 0, driver = RecordingDriver())

            assertEquals(listOf(1L), f.segments.deletedFor)
            assertEquals(1, f.segments.stored.size)
            assertEquals("[10,11]", f.segments.stored.single().matchedWayIds)
        }

    @Test
    fun `a manual walk reuses the route editor's segments and never map-matches`() =
        runBlocking {
            val drawn =
                RouteSegment(
                    walkId = 1L,
                    geometryJson = lineString(52.0 to 13.0, 52.0 to 13.001),
                    matchedWayIds = "[21,22]",
                    segmentOrder = 0,
                )
            val f =
                fixture(
                    walk = walk(source = WalkSource.MANUAL, status = WalkStatus.MANUAL_DRAFT),
                    routing = MatchingEngine(Result.failure(IllegalStateException("must not be called"))),
                    segments = FakeRouteSegmentRepository(listOf(drawn)),
                )

            val disposition = f.session.calculate(1L, runAttemptCount = 0, driver = RecordingDriver())

            assertEquals(CalculationDisposition.SUCCESS, disposition)
            assertEquals(listOf(1L to listOf(21L, 22L)), f.coverage.computedFor)
            // The drawn geometry is measured, not re-matched: ~68 m of longitude at this latitude.
            assertTrue("expected a measured distance", f.walks.getWalkById(1L)!!.distanceM > 0.0)
            assertTrue("manual segments must be kept", f.segments.deletedFor.isEmpty())
        }

    // --- Graceful degradation --------------------------------------------------------------

    @Test
    fun `a recorded walk with fewer than two points completes without coverage`() =
        runBlocking {
            val f = fixture(points = listOf(point()))

            val disposition = f.session.calculate(1L, runAttemptCount = 0, driver = RecordingDriver())

            assertCompletedWithoutCoverage(f, disposition)
        }

    @Test
    fun `a recorded walk whose matching failed completes without coverage`() =
        runBlocking {
            val f = fixture(matchResult = Result.failure(IllegalStateException("no match")))

            val disposition = f.session.calculate(1L, runAttemptCount = 0, driver = RecordingDriver())

            assertCompletedWithoutCoverage(f, disposition)
        }

    @Test
    fun `a manual walk with no segments completes without coverage`() =
        runBlocking {
            val f = fixture(walk = walk(source = WalkSource.MANUAL, status = WalkStatus.MANUAL_DRAFT))

            val disposition = f.session.calculate(1L, runAttemptCount = 0, driver = RecordingDriver())

            assertCompletedWithoutCoverage(f, disposition)
        }

    @Test
    fun `unreadable manual geometry completes the walk without a distance rather than retrying`() =
        runBlocking {
            val broken = RouteSegment(walkId = 1L, geometryJson = "not json", matchedWayIds = "[1]", segmentOrder = 0)
            val f =
                fixture(
                    walk = walk(source = WalkSource.MANUAL, status = WalkStatus.MANUAL_DRAFT, distanceM = 42.0),
                    segments = FakeRouteSegmentRepository(listOf(broken)),
                )

            val disposition = f.session.calculate(1L, runAttemptCount = 0, driver = RecordingDriver())

            assertCompletedWithoutCoverage(f, disposition)
            // The walk keeps the distance it already carried — no length is invented or zeroed.
            assertEquals(42.0, f.walks.getWalkById(1L)!!.distanceM, 0.001)
            assertTrue(f.jobs.getJobForWalk(1L)!!.lastError!!.contains("Unreadable geometry"))
        }

    @Test
    fun `missing map assets complete the walk without coverage rather than retrying`() =
        runBlocking {
            val f = fixture(coverage = FakeCoverageEngine(failWith = FileNotFoundException("city.osm.pbf")))

            val disposition = f.session.calculate(1L, runAttemptCount = 0, driver = RecordingDriver())

            assertCompletedWithoutCoverage(f, disposition)
            assertTrue(f.jobs.getJobForWalk(1L)!!.lastError!!.contains("No map assets"))
        }

    private suspend fun assertCompletedWithoutCoverage(
        f: Fixture,
        disposition: CalculationDisposition,
    ) {
        assertEquals(CalculationDisposition.SUCCESS, disposition)
        assertTrue("no coverage may be computed", f.coverage.computedFor.isEmpty())
        assertEquals(WalkStatus.COMPLETED, f.walks.getWalkById(1L)!!.status)
        assertEquals(JobStatus.DONE, f.jobs.getJobForWalk(1L)!!.status)
    }

    // --- Abort on delete --------------------------------------------------------------------

    @Test
    fun `a walk deleted mid-Calculation writes no coverage and stays deleted`() =
        runBlocking {
            val f = fixture(walk = walk(status = WalkStatus.DELETED))

            val disposition = f.session.calculate(1L, runAttemptCount = 0, driver = RecordingDriver())

            assertEquals(CalculationDisposition.FAILURE, disposition)
            assertTrue(f.coverage.computedFor.isEmpty())
            assertEquals(WalkStatus.DELETED, f.walks.getWalkById(1L)!!.status)
            assertTrue("a deleted walk must not be re-synced", f.scheduler.syncEnqueued.isEmpty())
            assertEquals(JobStatus.DONE, f.jobs.getJobForWalk(1L)!!.status)
        }

    @Test
    fun `a walk that no longer exists fails without retrying`() =
        runBlocking {
            val f = fixture()

            val disposition = f.session.calculate(42L, runAttemptCount = 0, driver = RecordingDriver())

            assertEquals(CalculationDisposition.FAILURE, disposition)
            assertTrue(f.coverage.computedFor.isEmpty())
        }

    // --- Retry budget ------------------------------------------------------------------------

    @Test
    fun `a transient failure retries while the shared budget has attempts left`() =
        runBlocking {
            val f = fixture(coverage = FakeCoverageEngine(failWith = IllegalStateException("boom")))

            val disposition = f.session.calculate(1L, runAttemptCount = 0, driver = RecordingDriver())

            assertEquals(CalculationDisposition.RETRY, disposition)
            val job = f.jobs.getJobForWalk(1L)!!
            assertEquals(JobStatus.QUEUED, job.status)
            assertEquals("boom", job.lastError)
            // The walk is left in PENDING_MATCH for the retry, not completed.
            assertEquals(WalkStatus.PENDING_MATCH, f.walks.getWalkById(1L)!!.status)
        }

    @Test
    fun `a transient failure fails for good once the shared budget is spent`() =
        runBlocking {
            val f = fixture(coverage = FakeCoverageEngine(failWith = IllegalStateException("boom")))

            val disposition = f.session.calculate(1L, runAttemptCount = WorkRetryPolicy.MAX_RETRIES, driver = RecordingDriver())

            assertEquals(CalculationDisposition.FAILURE, disposition)
            assertEquals(JobStatus.FAILED, f.jobs.getJobForWalk(1L)!!.status)
        }

    // --- The driver's side --------------------------------------------------------------------

    @Test
    fun `a stopped run is handed back before matching, leaving the walk untouched`() =
        runBlocking {
            val f = fixture()
            val stopped =
                object : RecordingDriver() {
                    override val isStopped = true
                }

            val disposition = f.session.calculate(1L, runAttemptCount = 0, driver = stopped)

            assertEquals(CalculationDisposition.RETRY, disposition)
            assertTrue(f.coverage.computedFor.isEmpty())
            assertEquals(WalkStatus.PENDING_MATCH, f.walks.getWalkById(1L)!!.status)
        }

    @Test
    fun `an engine that is not ready is initialized first, and progress is published throughout`() =
        runBlocking {
            val engine =
                MatchingEngine(
                    Result.success(MatchResult(emptyList(), listOf(10L), lineString(52.0 to 13.0, 52.001 to 13.0), 5.0)),
                    ready = false,
                )
            val f = fixture(routing = engine)
            val driver = RecordingDriver()

            f.session.calculate(1L, runAttemptCount = 0, driver = driver)

            assertEquals(1, engine.initializeCount)
            assertNotNull(driver.steps.firstOrNull { it.second.contains("map engine") })
            assertEquals(95, driver.steps.map { it.first }.max())
        }

    @Test
    fun `matching runs inside the driver's progress window`() =
        runBlocking {
            var matchedInsideWindow = false
            var insideWindow = false
            val driver =
                object : RecordingDriver() {
                    override suspend fun <T> whileMatching(block: suspend () -> T): T {
                        insideWindow = true
                        return try {
                            block()
                        } finally {
                            insideWindow = false
                        }
                    }
                }
            val engine =
                object : RoutingEngine by MatchingEngine(
                    Result.success(MatchResult(emptyList(), listOf(1L), lineString(52.0 to 13.0), 1.0)),
                ) {
                    override suspend fun matchTrace(points: List<GpsPoint>): Result<MatchResult> {
                        matchedInsideWindow = insideWindow
                        return Result.success(MatchResult(emptyList(), listOf(1L), lineString(52.0 to 13.0), 1.0))
                    }
                }

            fixture(routing = engine).session.calculate(1L, runAttemptCount = 0, driver = driver)

            assertTrue("matching must run inside whileMatching", matchedInsideWindow)
        }

    @Test
    fun `a walk with no pending job is calculated all the same`() =
        runBlocking {
            val f =
                Fixture(
                    walks = FakeWalkRepository(listOf(walk())),
                    points = FakeGpsPointRepository().apply { inserted += listOf(point(), point(lat = 52.001)) },
                    jobs = FakePendingMatchJobRepository(),
                    routing = MatchingEngine(Result.success(MatchResult(emptyList(), listOf(10L), lineString(52.0 to 13.0), 7.0))),
                )

            val disposition = f.session.calculate(1L, runAttemptCount = 0, driver = RecordingDriver())

            assertEquals(CalculationDisposition.SUCCESS, disposition)
            assertNull(f.jobs.getJobForWalk(1L))
            assertEquals(WalkStatus.COMPLETED, f.walks.getWalkById(1L)!!.status)
        }
}
