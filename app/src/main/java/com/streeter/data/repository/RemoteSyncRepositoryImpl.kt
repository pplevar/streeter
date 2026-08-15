package com.streeter.data.repository

import com.streeter.data.remote.api.StreeterApiService
import com.streeter.data.remote.dto.GpsPointDto
import com.streeter.data.remote.dto.GpsTraceSyncRequest
import com.streeter.data.remote.dto.WalkSyncRequest
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.model.Walk
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.repository.GpsPointRepository
import com.streeter.domain.repository.RemoteSyncRepository
import com.streeter.domain.repository.WalkRepository
import com.streeter.domain.sync.SyncCursor
import com.streeter.domain.work.WalkRecalculator
import com.streeter.domain.work.WalkSyncFinalizer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteSyncRepositoryImpl
    @Inject
    constructor(
        private val apiService: StreeterApiService,
        private val walkRepository: WalkRepository,
        private val gpsPointRepository: GpsPointRepository,
        private val walkRecalculator: WalkRecalculator,
        private val walkSyncFinalizer: WalkSyncFinalizer,
        private val syncCursor: SyncCursor,
    ) : RemoteSyncRepository {
        override suspend fun syncWalk(walkId: Long): Result<Unit> =
            runCatching {
                val walk =
                    walkRepository.getWalkById(walkId)
                        ?: error("Walk $walkId not found")

                val clientId = syncCursor.clientId()

                val response = apiService.syncWalk(walk.toSyncRequest(clientId))
                val serverWalkId = response.serverWalkId

                val points = gpsPointRepository.getPointsForWalk(walkId)
                val clientKnownUpdatedAt = walkRepository.getGpsTraceSyncedAt(walkId)
                val traceResponse =
                    apiService.syncGpsTrace(
                        serverWalkId,
                        GpsTraceSyncRequest(points.map { it.toDto() }, clientKnownUpdatedAt),
                    )
                if (traceResponse.accepted) {
                    walkRepository.updateGpsTraceSyncedAt(walkId, traceResponse.updatedAt)
                }

                walkSyncFinalizer.succeed(walkId, serverWalkId)
            }

        override suspend fun pullWalks(): Result<Unit> =
            runCatching {
                val since = syncCursor.pullSince()
                val pageSize = 100
                var offset = 0
                var lastSyncedAt = since

                while (true) {
                    val page = apiService.getWalks(since, pageSize, offset)
                    if (page.isEmpty()) break

                    page.forEach { dto ->
                        walkRepository.upsertFromRemote(dto)
                        if (dto.serverUpdatedAt > lastSyncedAt) lastSyncedAt = dto.serverUpdatedAt

                        if (dto.status == WalkStatus.DELETED.name) return@forEach

                        val localWalk =
                            walkRepository.getWalkByServerWalkId(dto.serverWalkId)
                                ?: return@forEach
                        val serverTraceUpdatedAt = dto.gpsTraceUpdatedAt ?: return@forEach
                        val localTraceUpdatedAt = walkRepository.getGpsTraceSyncedAt(localWalk.id)

                        if (localTraceUpdatedAt == null || serverTraceUpdatedAt > localTraceUpdatedAt) {
                            val trace = apiService.getGpsTrace(dto.serverWalkId)
                            gpsPointRepository.replacePointsFromRemote(
                                localWalk.id,
                                trace.points.map { it.toDomain(localWalk.id) },
                            )
                            walkRepository.updateGpsTraceSyncedAt(localWalk.id, trace.updatedAt)
                            // Pulled trace changed: recompute coverage. No upfront Sync — the
                            // pulled walk is already durable on the server.
                            //
                            // This also restamps `updatedAt` to the local clock, over the server
                            // value `upsertFromRemote` just wrote. `upsertFromRemote` gates server
                            // updates on `dto.updatedAt > existing.updatedAt`, so a server edit
                            // stamped *behind* this device's clock is dropped — a window the width
                            // of the client/server clock skew. Issue #53 asks for the bump on every
                            // path; see the PR discussion before narrowing it here.
                            walkRecalculator.traceChanged(localWalk.id)
                        }
                    }

                    offset += page.size
                    if (page.size < pageSize) break
                }

                syncCursor.advancePullCursor(lastSyncedAt)
            }

        override suspend fun deleteWalk(walkId: Long): Result<Unit> =
            runCatching {
                // Already hard-deleted (e.g. a pull converged the tombstone first): nothing to do.
                val walk = walkRepository.getWalkById(walkId) ?: return@runCatching
                // Never synced walks are hard-deleted locally without ever reaching this path; guard anyway.
                val serverWalkId = walk.serverWalkId ?: return@runCatching

                val confirmed = apiService.deleteWalk(serverWalkId)
                check(confirmed) { "Server rejected delete for walk $walkId (serverWalkId=$serverWalkId)" }

                walkRepository.hardDeleteWalk(walkId)
            }
    }

private fun Walk.toSyncRequest(clientId: String) =
    WalkSyncRequest(
        clientId = clientId,
        localWalkId = id,
        serverWalkId = serverWalkId,
        title = title,
        date = date,
        durationMs = durationMs,
        distanceM = distanceM,
        status = status.name,
        source = source.name,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun GpsPoint.toDto() =
    GpsPointDto(
        lat = lat,
        lng = lng,
        timestamp = timestamp,
        accuracyM = accuracyM,
        speedKmh = speedKmh,
        isFiltered = isFiltered,
        isManual = isManual,
    )

private fun GpsPointDto.toDomain(walkId: Long) =
    GpsPoint(
        walkId = walkId,
        lat = lat,
        lng = lng,
        timestamp = timestamp,
        accuracyM = accuracyM,
        speedKmh = speedKmh,
        isFiltered = isFiltered,
        isManual = isManual,
    )
