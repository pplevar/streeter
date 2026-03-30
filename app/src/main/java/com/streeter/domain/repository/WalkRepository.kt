package com.streeter.domain.repository

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
}
