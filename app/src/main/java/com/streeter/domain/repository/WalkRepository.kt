package com.streeter.domain.repository

import com.streeter.data.remote.dto.WalkSyncDto
import com.streeter.domain.model.*
import kotlinx.coroutines.flow.Flow

interface WalkRepository {
    fun getAllWalks(): Flow<List<Walk>>

    fun observeWalk(id: Long): Flow<Walk?>

    suspend fun getWalkById(id: Long): Walk?

    suspend fun insertWalk(walk: Walk): Long

    suspend fun updateWalk(walk: Walk)

    suspend fun deleteWalk(id: Long)

    suspend fun getActiveRecordingWalk(): Walk?

    fun getWalkWithCoverage(walkId: Long): Flow<List<WalkStreetCoverage>>

    suspend fun getWalksPendingSync(): List<Walk>

    suspend fun updateSyncStatus(
        id: Long,
        syncStatus: SyncStatus,
        serverWalkId: Long?,
    )

    suspend fun getWalkByServerWalkId(serverWalkId: Long): Walk?

    suspend fun getLastPullSyncAt(): Long?

    suspend fun upsertFromRemote(dto: WalkSyncDto)

    suspend fun updateLastPullSyncAt(id: Long, timestamp: Long)
}
