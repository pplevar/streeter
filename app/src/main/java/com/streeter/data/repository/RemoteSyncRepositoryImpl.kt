package com.streeter.data.repository

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.streeter.data.remote.api.StreeterApiService
import com.streeter.data.remote.dto.GpsPointDto
import com.streeter.data.remote.dto.GpsTraceSyncRequest
import com.streeter.data.remote.dto.WalkSyncRequest
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.model.SyncStatus
import com.streeter.domain.model.Walk
import com.streeter.domain.model.WalkStatus
import com.streeter.domain.repository.GpsPointRepository
import com.streeter.domain.repository.RemoteSyncRepository
import com.streeter.domain.repository.WalkRepository
import com.streeter.work.MapMatchingWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteSyncRepositoryImpl
    @Inject
    constructor(
        private val apiService: StreeterApiService,
        private val walkRepository: WalkRepository,
        private val gpsPointRepository: GpsPointRepository,
        @ApplicationContext private val context: Context,
    ) : RemoteSyncRepository {
        override suspend fun syncWalk(walkId: Long): Result<Unit> =
            runCatching {
                val walk =
                    walkRepository.getWalkById(walkId)
                        ?: error("Walk $walkId not found")

                val clientId = getOrCreateClientId()

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

                walkRepository.updateSyncStatus(walkId, SyncStatus.SYNCED, serverWalkId)
            }

        override suspend fun pullWalks(since: Long): Result<Unit> =
            runCatching {
                val pageSize = 100
                var offset = 0
                var lastSyncedAt = since
                val workManager = WorkManager.getInstance(context)

                while (true) {
                    val page = apiService.getWalks(since, pageSize, offset)
                    if (page.isEmpty()) break

                    page.forEach { dto ->
                        walkRepository.upsertFromRemote(dto)
                        if (dto.serverUpdatedAt > lastSyncedAt) lastSyncedAt = dto.serverUpdatedAt

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
                            walkRepository.updateWalk(localWalk.copy(status = WalkStatus.PENDING_MATCH))
                            workManager.enqueueUniqueWork(
                                "match_${localWalk.id}",
                                ExistingWorkPolicy.REPLACE,
                                MapMatchingWorker.buildRequest(localWalk.id),
                            )
                        }
                    }

                    offset += page.size
                    if (page.size < pageSize) break
                }

                if (lastSyncedAt > since) {
                    val prefs = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putLong("last_pull_sync_at", lastSyncedAt).apply()
                }
            }

        private fun getOrCreateClientId(): String {
            val prefs = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
            return prefs.getString("client_id", null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("client_id", it).apply()
            }
        }
    }

private fun Walk.toSyncRequest(clientId: String) =
    WalkSyncRequest(
        clientId = clientId,
        localWalkId = id,
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
