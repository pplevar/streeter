package com.streeter.data.repository

import com.streeter.data.local.dao.StreetDao
import com.streeter.data.local.dao.WalkDao
import com.streeter.data.local.entity.WalkEntity
import com.streeter.data.local.mapper.toCoverage
import com.streeter.data.local.mapper.toDomain
import com.streeter.data.local.mapper.toEntity
import com.streeter.data.remote.dto.WalkSyncDto
import com.streeter.domain.model.SyncStatus
import com.streeter.domain.model.Walk
import com.streeter.domain.model.WalkStreetCoverage
import com.streeter.domain.repository.WalkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class WalkRepositoryImpl
    @Inject
    constructor(
        private val walkDao: WalkDao,
        private val streetDao: StreetDao,
    ) : WalkRepository {
        override fun getAllWalks(): Flow<List<Walk>> = walkDao.getAllWalks().map { list -> list.map { it.toDomain() } }

        override fun observeWalk(id: Long): Flow<Walk?> = walkDao.observeById(id).map { it?.toDomain() }

        override suspend fun getWalkById(id: Long): Walk? = walkDao.getById(id)?.toDomain()

        override suspend fun insertWalk(walk: Walk): Long = walkDao.insert(walk.toEntity())

        override suspend fun updateWalk(walk: Walk) = walkDao.update(walk.toEntity())

        override suspend fun deleteWalk(id: Long) = walkDao.softDelete(id)

        override suspend fun getActiveRecordingWalk(): Walk? = walkDao.getActiveRecording()?.toDomain()

        override fun getWalkWithCoverage(walkId: Long): Flow<List<WalkStreetCoverage>> =
            streetDao.observeWalkCoverage(walkId).map { list -> list.map { it.toCoverage() } }

        override suspend fun getWalksPendingSync(): List<Walk> = walkDao.getPendingSync().map { it.toDomain() }

        override suspend fun updateSyncStatus(
            id: Long,
            syncStatus: SyncStatus,
            serverWalkId: Long?,
        ) = walkDao.updateSyncStatus(id, syncStatus.name, serverWalkId)

        override suspend fun getWalkByServerWalkId(serverWalkId: Long): Walk? = walkDao.getWalkByServerWalkId(serverWalkId)?.toDomain()

        override suspend fun getLastPullSyncAt(): Long? = walkDao.getLastPullSyncAt()

        override suspend fun upsertFromRemote(dto: WalkSyncDto) {
            val existing = walkDao.getWalkByServerWalkId(dto.serverWalkId)
            if (existing == null) {
                walkDao.insert(dto.toNewEntity())
            } else if (dto.updatedAt > existing.updatedAt) {
                walkDao.update(
                    existing.copy(
                        title = dto.title,
                        durationMs = dto.durationMs,
                        distanceM = dto.distanceM,
                        status = dto.status,
                        updatedAt = dto.updatedAt,
                        syncStatus = SyncStatus.SYNCED.name,
                    ),
                )
            }
        }

        override suspend fun updateLastPullSyncAt(
            id: Long,
            timestamp: Long,
        ) = walkDao.updateLastPullSyncAt(id, timestamp)
    }

private fun WalkSyncDto.toNewEntity() =
    WalkEntity(
        title = title,
        date = date,
        durationMs = durationMs,
        distanceM = distanceM,
        status = status,
        source = source,
        createdAt = createdAt,
        updatedAt = updatedAt,
        syncStatus = SyncStatus.SYNCED.name,
        serverWalkId = serverWalkId,
    )
