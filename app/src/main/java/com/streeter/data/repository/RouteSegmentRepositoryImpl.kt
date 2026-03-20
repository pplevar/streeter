package com.streeter.data.repository

import com.streeter.data.local.dao.RouteSegmentDao
import com.streeter.data.local.entity.RouteSegmentEntity
import com.streeter.domain.model.RouteSegment
import com.streeter.domain.repository.RouteSegmentRepository
import javax.inject.Inject

class RouteSegmentRepositoryImpl @Inject constructor(
    private val dao: RouteSegmentDao
) : RouteSegmentRepository {

    override suspend fun insertSegment(segment: RouteSegment): Long =
        dao.insert(RouteSegmentEntity(
            walkId = segment.walkId, geometryJson = segment.geometryJson,
            matchedWayIds = segment.matchedWayIds, segmentOrder = segment.segmentOrder
        ))

    override suspend fun getSegmentsForWalk(walkId: Long): List<RouteSegment> =
        dao.getSegmentsForWalk(walkId).map {
            RouteSegment(it.id, it.walkId, it.geometryJson, it.matchedWayIds, it.segmentOrder)
        }

    override suspend fun deleteSegmentsForWalk(walkId: Long) =
        dao.deleteForWalk(walkId)
}
