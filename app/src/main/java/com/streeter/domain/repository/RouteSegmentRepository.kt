package com.streeter.domain.repository

import com.streeter.domain.model.RouteSegment

interface RouteSegmentRepository {
    suspend fun insertSegment(segment: RouteSegment): Long

    suspend fun getSegmentsForWalk(walkId: Long): List<RouteSegment>

    suspend fun deleteSegmentsForWalk(walkId: Long)
}
