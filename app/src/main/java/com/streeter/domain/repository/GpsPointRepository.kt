package com.streeter.domain.repository

import com.streeter.domain.model.GpsPoint
import kotlinx.coroutines.flow.Flow

interface GpsPointRepository {
    suspend fun insertPoints(points: List<GpsPoint>)

    suspend fun getPointsForWalk(walkId: Long): List<GpsPoint>

    suspend fun getPointsForMapMatching(walkId: Long): List<GpsPoint>

    fun observePointsForWalk(walkId: Long): Flow<List<GpsPoint>>
}
