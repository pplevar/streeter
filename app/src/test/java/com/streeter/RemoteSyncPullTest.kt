package com.streeter

import com.streeter.data.remote.api.StreeterApiService
import com.streeter.data.remote.dto.GpsPointDto
import com.streeter.data.remote.dto.GpsTraceResponse
import com.streeter.data.remote.dto.WalkSyncDto
import com.streeter.data.repository.RemoteSyncRepositoryImpl
import com.streeter.di.configureStreeterClient
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.repository.GpsPointRepository
import com.streeter.domain.work.WalkRecalculator
import com.streeter.domain.work.WalkSyncFinalizer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural spec for the pull feed (issue #54). Now that the sync module's only Android
 * dependency — the pull cursor and client id — sits behind `SyncCursor`, the whole module is
 * constructible on the JVM: a Ktor `MockEngine` plays the server and in-memory adapters stand in
 * for storage.
 *
 * Two decisions are locked here: how the pagination loop walks the feed and advances the cursor,
 * and when a pulled walk's GPS Trace is considered stale enough to re-download.
 */
class RemoteSyncPullTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun walkDto(
        serverWalkId: Long,
        serverUpdatedAt: Long,
        status: WalkStatus = WalkStatus.COMPLETED,
        gpsTraceUpdatedAt: Long? = null,
    ) = WalkSyncDto(
        serverWalkId = serverWalkId,
        localWalkId = 0L,
        clientId = "other-device",
        title = "Evening loop",
        date = 0L,
        durationMs = 0L,
        distanceM = 0.0,
        status = status.name,
        source = "RECORDED",
        createdAt = 0L,
        updatedAt = serverUpdatedAt,
        serverUpdatedAt = serverUpdatedAt,
        gpsTraceUpdatedAt = gpsTraceUpdatedAt,
    )

    private fun traceResponse(
        serverWalkId: Long,
        updatedAt: Long,
        pointCount: Int = 2,
    ) = GpsTraceResponse(
        walkId = serverWalkId,
        pointCount = pointCount,
        points =
            (1..pointCount).map {
                GpsPointDto(
                    lat = it.toDouble(),
                    lng = it.toDouble(),
                    timestamp = it.toLong(),
                    accuracyM = 5f,
                    speedKmh = 4f,
                    isFiltered = false,
                )
            },
        updatedAt = updatedAt,
    )

    /**
     * Wires a sync module around a server that answers `GET /walks` from [pages] (one entry per
     * request, in order) and every `GET /walks/{id}/gps-trace` from [traces].
     */
    private fun fixture(
        pages: List<List<WalkSyncDto>>,
        traces: Map<Long, GpsTraceResponse> = emptyMap(),
        cursor: Long = 0L,
        walkRepository: FakeWalkRepository = FakeWalkRepository(),
    ): Fixture {
        val requests = mutableListOf<HttpRequestData>()
        val engine =
            MockEngine { request ->
                requests += request
                val path = request.url.encodedPath
                val body =
                    when {
                        path.endsWith("/gps-trace") -> {
                            val serverWalkId = path.split("/").let { it[it.size - 2] }.toLong()
                            json.encodeToString(
                                traces[serverWalkId] ?: error("no trace staged for walk $serverWalkId"),
                            )
                        }
                        else -> {
                            val pageIndex = requests.count { it.url.encodedPath == path } - 1
                            json.encodeToString(pages.getOrElse(pageIndex) { emptyList() })
                        }
                    }
                respond(body, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
        val client = HttpClient(engine) { configureStreeterClient(tokenProvider = { "" }, debug = false) }
        val gpsPointRepository = RecordingGpsPointRepository()
        val scheduler = FakeWalkWorkScheduler()
        val syncCursor = InMemorySyncCursor(cursor = cursor)
        val repository =
            RemoteSyncRepositoryImpl(
                apiService = StreeterApiService(client, "http://localhost"),
                walkRepository = walkRepository,
                gpsPointRepository = gpsPointRepository,
                walkRecalculator = WalkRecalculator(walkRepository, scheduler),
                walkSyncFinalizer = WalkSyncFinalizer(walkRepository),
                syncCursor = syncCursor,
            )
        return Fixture(repository, requests, walkRepository, gpsPointRepository, scheduler, syncCursor)
    }

    private class Fixture(
        val repository: RemoteSyncRepositoryImpl,
        val requests: List<HttpRequestData>,
        val walkRepository: FakeWalkRepository,
        val gpsPointRepository: RecordingGpsPointRepository,
        val scheduler: FakeWalkWorkScheduler,
        val syncCursor: InMemorySyncCursor,
    ) {
        /** The `GET /walks` calls, in order — trace downloads excluded. */
        val feedRequests get() = requests.filter { !it.url.encodedPath.endsWith("/gps-trace") }

        fun feedParam(
            index: Int,
            name: String,
        ) = feedRequests[index].url.parameters[name]
    }

    // --- Pagination ------------------------------------------------------------------------------

    @Test
    fun `a short page ends the feed without a further request`() =
        runBlocking {
            // 40 < the page size of 100, so the server has nothing more to give.
            val f = fixture(pages = listOf((1L..40L).map { walkDto(it, serverUpdatedAt = it) }))

            f.repository.pullWalks().getOrThrow()

            assertEquals(1, f.feedRequests.size)
            assertEquals(40, f.walkRepository.remoteUpserts.size)
        }

    @Test
    fun `a full page is followed by the next page, offset by what was consumed`() =
        runBlocking {
            val f =
                fixture(
                    pages =
                        listOf(
                            (1L..100L).map { walkDto(it, serverUpdatedAt = it) },
                            (101L..130L).map { walkDto(it, serverUpdatedAt = it) },
                        ),
                    cursor = 5L,
                )

            f.repository.pullWalks().getOrThrow()

            assertEquals(2, f.feedRequests.size)
            assertEquals("0", f.feedParam(0, "offset"))
            assertEquals("100", f.feedParam(1, "offset"))
            // `since` stays the cursor value the pull started from across pages — paging is by
            // offset, so re-anchoring mid-run would skip walks.
            assertEquals("5", f.feedParam(0, "since"))
            assertEquals("5", f.feedParam(1, "since"))
            assertEquals(130, f.walkRepository.remoteUpserts.size)
        }

    @Test
    fun `an exactly-full final page is confirmed by an empty page`() =
        runBlocking {
            // 100 == the page size, so the feed cannot tell it is done; it asks once more.
            val f = fixture(pages = listOf((1L..100L).map { walkDto(it, serverUpdatedAt = it) }, emptyList()))

            f.repository.pullWalks().getOrThrow()

            assertEquals(2, f.feedRequests.size)
            assertEquals(100, f.walkRepository.remoteUpserts.size)
        }

    @Test
    fun `every page is requested with includeDeleted so tombstones converge`() =
        runBlocking {
            // ADR-0003: pull is an add/update feed, so a deletion can only arrive as a tombstone.
            val f = fixture(pages = listOf(listOf(walkDto(1L, serverUpdatedAt = 10L))))

            f.repository.pullWalks().getOrThrow()

            assertEquals("true", f.feedParam(0, "includeDeleted"))
        }

    // --- Cursor ----------------------------------------------------------------------------------

    @Test
    fun `the cursor advances to the newest serverUpdatedAt the pull saw`() =
        runBlocking {
            val f =
                fixture(
                    pages = listOf(listOf(walkDto(1L, serverUpdatedAt = 30L), walkDto(2L, serverUpdatedAt = 70L))),
                    cursor = 10L,
                )

            f.repository.pullWalks().getOrThrow()

            assertEquals(70L, f.syncCursor.pullSince())
        }

    @Test
    fun `an empty feed leaves the cursor where it was`() =
        runBlocking {
            val f = fixture(pages = listOf(emptyList()), cursor = 42L)

            f.repository.pullWalks().getOrThrow()

            assertEquals(42L, f.syncCursor.pullSince())
        }

    // --- Per-walk trace freshness ----------------------------------------------------------------

    @Test
    fun `a server trace newer than the local one is downloaded and recalculated`() =
        runBlocking {
            val walks = FakeWalkRepository(listOf(testWalk(id = 7L, serverWalkId = 1L)))
            walks.traceSyncedAt[7L] = 100L
            val f =
                fixture(
                    pages = listOf(listOf(walkDto(1L, serverUpdatedAt = 200L, gpsTraceUpdatedAt = 200L))),
                    traces = mapOf(1L to traceResponse(serverWalkId = 1L, updatedAt = 200L)),
                    walkRepository = walks,
                )

            f.repository.pullWalks().getOrThrow()

            assertEquals(listOf(7L), f.gpsPointRepository.replacedWalks)
            assertEquals(2, f.gpsPointRepository.replacedPoints.size)
            // The freshness stamp moves to the server's, so the next pull leaves this trace alone.
            assertEquals(200L, walks.traceSyncedAt[7L])
            // A changed Trace makes Calculation stale (issue #53).
            assertEquals(listOf(7L), f.scheduler.calculationEnqueued)
            assertTrue(f.scheduler.syncEnqueued.isEmpty())
        }

    @Test
    fun `a walk whose trace this device already has at the server's stamp is left alone`() =
        runBlocking {
            val walks = FakeWalkRepository(listOf(testWalk(id = 7L, serverWalkId = 1L)))
            walks.traceSyncedAt[7L] = 200L
            val f =
                fixture(
                    pages = listOf(listOf(walkDto(1L, serverUpdatedAt = 300L, gpsTraceUpdatedAt = 200L))),
                    walkRepository = walks,
                )

            f.repository.pullWalks().getOrThrow()

            // Metadata still lands; only the (unchanged) trace download is skipped.
            assertEquals(1, walks.remoteUpserts.size)
            assertTrue(f.gpsPointRepository.replacedWalks.isEmpty())
            assertTrue(f.scheduler.calculationEnqueued.isEmpty())
        }

    @Test
    fun `a walk this device has never held its trace for is downloaded`() =
        runBlocking {
            val walks = FakeWalkRepository(listOf(testWalk(id = 7L, serverWalkId = 1L)))
            val f =
                fixture(
                    pages = listOf(listOf(walkDto(1L, serverUpdatedAt = 300L, gpsTraceUpdatedAt = 200L))),
                    traces = mapOf(1L to traceResponse(serverWalkId = 1L, updatedAt = 200L)),
                    walkRepository = walks,
                )

            f.repository.pullWalks().getOrThrow()

            assertEquals(listOf(7L), f.gpsPointRepository.replacedWalks)
        }

    @Test
    fun `a walk the server has no trace for is never asked for one`() =
        runBlocking {
            val walks = FakeWalkRepository(listOf(testWalk(id = 7L, serverWalkId = 1L)))
            val f =
                fixture(
                    pages = listOf(listOf(walkDto(1L, serverUpdatedAt = 300L, gpsTraceUpdatedAt = null))),
                    walkRepository = walks,
                )

            f.repository.pullWalks().getOrThrow()

            assertTrue(f.gpsPointRepository.replacedWalks.isEmpty())
        }

    @Test
    fun `a tombstone never triggers a trace download`() =
        runBlocking {
            // The server discards a deleted walk's Trace, so asking for one would 404 (ADR-0003).
            val walks = FakeWalkRepository(listOf(testWalk(id = 7L, serverWalkId = 1L)))
            val f =
                fixture(
                    pages =
                        listOf(
                            listOf(
                                walkDto(
                                    1L,
                                    serverUpdatedAt = 300L,
                                    status = WalkStatus.DELETED,
                                    gpsTraceUpdatedAt = 300L,
                                ),
                            ),
                        ),
                    walkRepository = walks,
                )

            f.repository.pullWalks().getOrThrow()

            // The tombstone itself still lands, so the local row converges away.
            assertEquals(1, walks.remoteUpserts.size)
            assertTrue(f.gpsPointRepository.replacedWalks.isEmpty())
            assertEquals(300L, f.syncCursor.pullSince())
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
