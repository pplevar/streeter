package com.streeter

import com.streeter.data.engine.TransactionRunner
import com.streeter.data.remote.dto.WalkSyncDto
import com.streeter.domain.engine.RoutingEngine
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.model.LatLng
import com.streeter.domain.model.MatchResult
import com.streeter.domain.model.PendingMatchJob
import com.streeter.domain.model.RouteResult
import com.streeter.domain.model.Street
import com.streeter.domain.model.StreetSection
import com.streeter.domain.model.StreetWalkEntry
import com.streeter.domain.model.SyncStatus
import com.streeter.domain.model.Walk
import com.streeter.domain.model.WalkSectionCoverage
import com.streeter.domain.model.WalkSource
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.model.WalkStreetCoverage
import com.streeter.domain.recording.WakeGuard
import com.streeter.domain.repository.GpsPointRepository
import com.streeter.domain.repository.PendingMatchJobRepository
import com.streeter.domain.repository.StreetRepository
import com.streeter.domain.repository.WalkRepository
import com.streeter.domain.sync.SyncCursor
import com.streeter.domain.time.Clock
import com.streeter.domain.work.WalkWorkScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// Test fixtures shared across unit tests.
//
// Keep these minimal — production code should be exercised through the real interfaces.
// Each fake records its inputs where the test needs to inspect them.

/** A [Walk] with inert defaults; name only the fields the test is about. */
internal fun testWalk(
    id: Long = 1L,
    status: WalkStatus = WalkStatus.COMPLETED,
    syncStatus: SyncStatus = SyncStatus.PENDING_SYNC,
    serverWalkId: Long? = null,
    distanceM: Double = 0.0,
) = Walk(
    id = id,
    title = null,
    date = 0L,
    durationMs = 0L,
    distanceM = distanceM,
    status = status,
    source = WalkSource.RECORDED,
    createdAt = 0L,
    updatedAt = 0L,
    syncStatus = syncStatus,
    serverWalkId = serverWalkId,
)

/** Runs the supplied block inline — sufficient for unit tests that don't need real Room transactions. */
internal val InlineTransactionRunner = TransactionRunner { block -> block() }

/**
 * In-memory [StreetRepository] suitable for engine unit tests.
 * Captures all upserts/inserts so tests can assert on the actual values written.
 */
internal class RecordingStreetRepository : StreetRepository {
    val upsertedStreets = mutableListOf<Street>()
    val upsertedSections = mutableListOf<StreetSection>()
    val insertedWalkStreetCoverages = mutableListOf<WalkStreetCoverage>()
    val insertedWalkSectionCoverages = mutableListOf<WalkSectionCoverage>()
    val deletedWalkCoverages = mutableListOf<Long>()

    /** Stable-ids the test pretends already exist in the DB (returned from [getSectionsByStableIds]). */
    var existingStableIds: Set<String> = emptySet()

    private var streetIdSeq = 0L

    override suspend fun upsertStreet(street: Street): Long {
        upsertedStreets += street
        return ++streetIdSeq
    }

    override suspend fun upsertStreets(streets: List<Street>): List<Long> {
        upsertedStreets += streets
        return streets.map { ++streetIdSeq }
    }

    override suspend fun getSectionsByStreetId(streetId: Long): List<StreetSection> = emptyList()

    override suspend fun upsertSection(section: StreetSection) {
        upsertedSections += section
    }

    override suspend fun upsertSections(sections: List<StreetSection>) {
        upsertedSections += sections
    }

    override suspend fun getSectionByStableId(stableId: String): StreetSection? = null

    override suspend fun getSectionsByStableIds(stableIds: List<String>): List<StreetSection> =
        stableIds
            .filter { it in existingStableIds }
            .map { sid ->
                StreetSection(
                    id = 0,
                    streetId = 0,
                    fromNodeOsmId = 0,
                    toNodeOsmId = 0,
                    lengthM = 0.0,
                    geometryJson = "",
                    stableId = sid,
                    isOrphaned = false,
                )
            }

    override suspend fun insertWalkStreetCoverage(coverage: WalkStreetCoverage) {
        insertedWalkStreetCoverages += coverage
    }

    override suspend fun insertWalkStreetCoverages(coverages: List<WalkStreetCoverage>) {
        insertedWalkStreetCoverages += coverages
    }

    override suspend fun insertWalkSectionCoverage(coverage: WalkSectionCoverage) {
        insertedWalkSectionCoverages += coverage
    }

    override suspend fun insertWalkSectionCoverages(coverages: List<WalkSectionCoverage>) {
        insertedWalkSectionCoverages += coverages
    }

    override suspend fun deleteWalkCoverageForWalk(walkId: Long) {
        deletedWalkCoverages += walkId
    }

    override suspend fun getStreetCoverageForWalk(walkId: Long): List<WalkStreetCoverage> = emptyList()

    override suspend fun getStreetCountForWalk(walkId: Long): Int = 0

    override fun observeCoveredStreetCount(): Flow<Int> = flowOf(0)

    override fun observeTotalStreetCount(): Flow<Int> = flowOf(0)

    override suspend fun getStreetById(streetId: Long): Street? = null

    override suspend fun getCoveredLengthForStreet(streetId: Long): Double = 0.0

    override suspend fun getWalksForStreet(streetId: Long): List<StreetWalkEntry> = emptyList()

    override suspend fun getCoveredSectionEdgeIdsForWalk(
        walkId: Long,
        streetId: Long,
    ): List<Long> = emptyList()
}

/**
 * In-memory [WalkRepository] holding a single mutable [Walk] keyed by id.
 * Only the methods exercised by Calculation-finalization tests are backed; the rest
 * are inert so the fake stays minimal.
 */
internal class FakeWalkRepository(
    walks: List<Walk> = emptyList(),
) : WalkRepository {
    private val store = walks.associateBy { it.id }.toMutableMap()
    private var idSeq = store.keys.maxOrNull() ?: 0L

    /** Everything currently stored, for tests that assert on how many walks were created. */
    val walks: List<Walk> get() = store.values.toList()

    override suspend fun getWalkById(id: Long): Walk? = store[id]

    override suspend fun updateWalk(walk: Walk) {
        store[walk.id] = walk
    }

    override suspend fun updateSyncStatus(
        id: Long,
        syncStatus: SyncStatus,
        serverWalkId: Long?,
    ) {
        store[id]?.let { store[id] = it.copy(syncStatus = syncStatus, serverWalkId = serverWalkId) }
    }

    /** Mirrors the status-only DAO update: `serverWalkId` is left as-is. */
    override suspend fun markSyncFailed(id: Long) {
        store[id]?.let { store[id] = it.copy(syncStatus = SyncStatus.SYNC_FAILED) }
    }

    /** Mirrors Room: an unsaved walk (id 0) is assigned a fresh row id. */
    override suspend fun insertWalk(walk: Walk): Long {
        val id = if (walk.id == 0L) ++idSeq else walk.id
        store[id] = walk.copy(id = id)
        return id
    }

    override suspend fun markWalkDeleted(id: Long) {
        store[id]?.let { store[id] = it.copy(status = WalkStatus.DELETED) }
    }

    override suspend fun hardDeleteWalk(id: Long) {
        store.remove(id)
    }

    override fun getAllWalks(): Flow<List<Walk>> = flowOf(store.values.toList())

    override fun observeWalk(id: Long): Flow<Walk?> = flowOf(store[id])

    override suspend fun getActiveRecordingWalk(): Walk? = null

    override fun getWalkWithCoverage(walkId: Long): Flow<List<WalkStreetCoverage>> = flowOf(emptyList())

    override suspend fun getWalksPendingSync(): List<Walk> = emptyList()

    override suspend fun getWalkByServerWalkId(serverWalkId: Long): Walk? = store.values.firstOrNull { it.serverWalkId == serverWalkId }

    /** Every dto handed to [upsertFromRemote], in arrival order. */
    val remoteUpserts = mutableListOf<WalkSyncDto>()

    /** Per-walk GPS-trace freshness stamp; seed it to model a trace this device already holds. */
    val traceSyncedAt = mutableMapOf<Long, Long>()

    override suspend fun upsertFromRemote(dto: WalkSyncDto) {
        remoteUpserts += dto
    }

    override suspend fun getGpsTraceSyncedAt(id: Long): Long? = traceSyncedAt[id]

    override suspend fun updateGpsTraceSyncedAt(
        id: Long,
        timestamp: Long,
    ) {
        traceSyncedAt[id] = timestamp
    }
}

/** Records the trace writes the pull feed makes; every other read is inert. */
internal class RecordingGpsPointRepository : GpsPointRepository {
    val replacedWalks = mutableListOf<Long>()
    val replacedPoints = mutableListOf<GpsPoint>()

    override suspend fun replacePointsFromRemote(
        walkId: Long,
        points: List<GpsPoint>,
    ) {
        replacedWalks += walkId
        replacedPoints += points
    }

    override suspend fun insertPoints(points: List<GpsPoint>) = Unit

    override suspend fun getPointsForWalk(walkId: Long): List<GpsPoint> = emptyList()

    override suspend fun getPointsForMapMatching(walkId: Long): List<GpsPoint> = emptyList()

    override fun observePointsForWalk(walkId: Long): Flow<List<GpsPoint>> = flowOf(emptyList())

    override suspend fun getPointsExcludingWalk(excludeWalkId: Long): List<GpsPoint> = emptyList()

    override suspend fun deletePoint(
        walkId: Long,
        pointId: Long,
    ): Int = 0
}

/**
 * In-memory [SyncCursor] — the test-side adapter that replaces app preferences, so the sync
 * module can be constructed and its pull feed driven entirely on the JVM (issue #54).
 */
internal class InMemorySyncCursor(
    private var cursor: Long = 0L,
    private val id: String = "test-client",
) : SyncCursor {
    override suspend fun pullSince(): Long = cursor

    override suspend fun advancePullCursor(serverUpdatedAt: Long) {
        if (serverUpdatedAt > cursor) cursor = serverUpdatedAt
    }

    override suspend fun clientId(): String = id
}

/** In-memory [PendingMatchJobRepository] keyed by walkId. */
internal class FakePendingMatchJobRepository(
    jobs: List<PendingMatchJob> = emptyList(),
) : PendingMatchJobRepository {
    private val store = jobs.associateBy { it.walkId }.toMutableMap()

    override suspend fun enqueue(job: PendingMatchJob): Long {
        store[job.walkId] = job
        return job.id
    }

    override suspend fun getJobForWalk(walkId: Long): PendingMatchJob? = store[walkId]

    override suspend fun updateJob(job: PendingMatchJob) {
        store[job.walkId] = job
    }

    override suspend fun deleteJobForWalk(walkId: Long) {
        store.remove(walkId)
    }
}

/** Records every scheduling call so tests can assert what was enqueued/cancelled. */
internal open class FakeWalkWorkScheduler : WalkWorkScheduler {
    val newWalkProcessing = mutableListOf<Long>()
    val calculationEnqueued = mutableListOf<Long>()
    val calculationCancelled = mutableListOf<Long>()
    val syncEnqueued = mutableListOf<Long>()
    val deleteEnqueued = mutableListOf<Long>()
    var pullEnqueued = 0
    var periodicPullScheduled = 0

    override fun enqueueNewWalkProcessing(walkId: Long) {
        newWalkProcessing += walkId
    }

    override fun enqueueCalculation(walkId: Long) {
        calculationEnqueued += walkId
    }

    override fun cancelCalculation(walkId: Long) {
        calculationCancelled += walkId
    }

    override fun enqueueSync(walkId: Long) {
        syncEnqueued += walkId
    }

    override fun enqueueDelete(walkId: Long) {
        deleteEnqueued += walkId
    }

    override fun enqueuePull() {
        pullEnqueued++
    }

    override fun schedulePeriodicPull() {
        periodicPullScheduled++
    }
}

/**
 * Programmable [RoutingEngine] — return values are looked up from per-way-id maps so tests can
 * model multi-way streets, missing names, etc. without touching GraphHopper.
 */
internal open class FakeRoutingEngine(
    private val streetNamesByWay: Map<Long, String> = emptyMap(),
    private val edgeLengthsByWay: Map<Long, Double> = emptyMap(),
    private val streetTotalLengths: Map<String, Double> = emptyMap(),
    /** What map matching returns; failure by default, so an unexpected match is loud. */
    private val matchResult: Result<MatchResult> = Result.failure(UnsupportedOperationException("fake")),
    private val ready: Boolean = true,
) : RoutingEngine {
    /** How often the session had to initialize the engine before using it. */
    var initializeCount = 0
        private set

    override suspend fun isReady() = ready

    override suspend fun initialize() {
        initializeCount++
    }

    override suspend fun matchTrace(points: List<GpsPoint>): Result<MatchResult> = matchResult

    override suspend fun route(
        from: LatLng,
        to: LatLng,
        via: List<LatLng>,
    ): Result<RouteResult> = Result.failure(UnsupportedOperationException("fake"))

    override fun getStreetName(edgeId: Long): String? = streetNamesByWay[edgeId]

    override fun findNearestNamedStreet(edgeId: Long): String? = null

    override fun getEdgeLength(edgeId: Long): Double? = edgeLengthsByWay[edgeId]

    override fun getStreetTotalLength(streetName: String): Double? = streetTotalLengths[streetName]

    override fun getEdgeGeometry(edgeId: Long): String? = null

    override fun getEdgeGeometriesForStreet(streetName: String): List<String> = emptyList()
}

/** A [Clock] the test moves by hand, so durations are exact instead of wall-clock flaky. */
internal class MutableClock(
    private var nowMs: Long = 0L,
) : Clock {
    override fun nowMillis(): Long = nowMs

    fun advance(millis: Long) {
        nowMs += millis
    }
}

/** Runs the guarded block directly — nothing to keep awake on the JVM. */
internal object NoopWakeGuard : WakeGuard {
    override suspend fun <T> whileAwake(block: suspend () -> T): T = block()
}

/**
 * In-memory [GpsPointRepository] that keeps every written point, and can be told to fail its
 * writes so tests can assert that a batch is not lost when the database rejects it.
 */
internal class FakeGpsPointRepository : GpsPointRepository {
    val inserted = mutableListOf<GpsPoint>()

    /** When true, [insertPoints] throws instead of persisting. */
    var failWrites = false

    override suspend fun insertPoints(points: List<GpsPoint>) {
        if (failWrites) throw IllegalStateException("write failed")
        inserted += points
    }

    override suspend fun getPointsForWalk(walkId: Long): List<GpsPoint> = inserted.filter { it.walkId == walkId && !it.isFiltered }

    override suspend fun getPointsForMapMatching(walkId: Long): List<GpsPoint> = getPointsForWalk(walkId)

    override fun observePointsForWalk(walkId: Long): Flow<List<GpsPoint>> = flowOf(emptyList())

    override suspend fun getPointsExcludingWalk(excludeWalkId: Long): List<GpsPoint> = inserted.filter { it.walkId != excludeWalkId }

    override suspend fun replacePointsFromRemote(
        walkId: Long,
        points: List<GpsPoint>,
    ) {
        inserted.removeAll { it.walkId == walkId }
        inserted += points
    }

    override suspend fun deletePoint(
        walkId: Long,
        pointId: Long,
    ): Int = 0
}
