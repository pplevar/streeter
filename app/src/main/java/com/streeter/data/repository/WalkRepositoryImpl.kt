package com.streeter.data.repository

import com.streeter.data.local.dao.StreetDao
import com.streeter.data.local.dao.WalkDao
import com.streeter.data.local.mapper.*
import com.streeter.domain.model.*
import com.streeter.domain.repository.WalkRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class WalkRepositoryImpl @Inject constructor(
    private val walkDao: WalkDao,
    private val streetDao: StreetDao
) : WalkRepository {

    override fun getAllWalks(): Flow<List<Walk>> =
        walkDao.getAllWalks().map { list -> list.map { it.toDomain() } }

    override suspend fun getWalkById(id: Long): Walk? =
        walkDao.getById(id)?.toDomain()

    override suspend fun insertWalk(walk: Walk): Long =
        walkDao.insert(walk.toEntity())

    override suspend fun updateWalk(walk: Walk) =
        walkDao.update(walk.toEntity())

    override suspend fun deleteWalk(id: Long) =
        walkDao.softDelete(id)

    override suspend fun getActiveRecordingWalk(): Walk? =
        walkDao.getActiveRecording()?.toDomain()

    override fun getWalkWithCoverage(walkId: Long): Flow<List<WalkStreetCoverage>> =
        streetDao.observeWalkCoverage(walkId).map { list -> list.map { it.toCoverage() } }
}
