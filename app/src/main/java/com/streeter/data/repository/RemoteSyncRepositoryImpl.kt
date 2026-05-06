package com.streeter.data.repository

import android.content.Context
import com.streeter.data.remote.api.StreeterApiService
import com.streeter.data.remote.dto.GpsPointDto
import com.streeter.data.remote.dto.GpsTraceSyncRequest
import com.streeter.data.remote.dto.WalkSyncRequest
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.model.SyncStatus
import com.streeter.domain.model.Walk
import com.streeter.domain.repository.GpsPointRepository
import com.streeter.domain.repository.RemoteSyncRepository
import com.streeter.domain.repository.WalkRepository
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
                apiService.syncGpsTrace(serverWalkId, GpsTraceSyncRequest(points.map { it.toDto() }))

                walkRepository.updateSyncStatus(walkId, SyncStatus.SYNCED, serverWalkId)
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
    )
