package com.streeter.data.repository

import com.streeter.data.local.dao.GpsPointDao
import com.streeter.data.local.mapper.toDomain
import com.streeter.data.local.mapper.toEntity
import com.streeter.domain.model.GpsPoint
import com.streeter.domain.repository.GpsPointRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class GpsPointRepositoryImpl
    @Inject
    constructor(
        private val dao: GpsPointDao,
    ) : GpsPointRepository {
        override suspend fun insertPoints(points: List<GpsPoint>) = dao.insertAll(points.map { it.toEntity() })

        override suspend fun getPointsForWalk(walkId: Long): List<GpsPoint> = dao.getUnfilteredPoints(walkId).map { it.toDomain() }

        override fun observePointsForWalk(walkId: Long): Flow<List<GpsPoint>> =
            dao.observePoints(walkId).map { list -> list.map { it.toDomain() } }
    }
