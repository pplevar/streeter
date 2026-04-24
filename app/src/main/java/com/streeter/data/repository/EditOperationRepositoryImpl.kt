package com.streeter.data.repository

import com.streeter.data.local.dao.EditOperationDao
import com.streeter.data.local.entity.EditOperationEntity
import com.streeter.domain.model.EditOperation
import com.streeter.domain.repository.EditOperationRepository
import javax.inject.Inject

class EditOperationRepositoryImpl
    @Inject
    constructor(
        private val dao: EditOperationDao,
    ) : EditOperationRepository {
        override suspend fun insertOperation(op: EditOperation): Long =
            dao.insert(
                EditOperationEntity(
                    walkId = op.walkId, operationOrder = op.operationOrder,
                    anchor1Lat = op.anchor1Lat, anchor1Lng = op.anchor1Lng,
                    anchor2Lat = op.anchor2Lat, anchor2Lng = op.anchor2Lng,
                    waypointLat = op.waypointLat, waypointLng = op.waypointLng,
                    replacedGeometryJson = op.replacedGeometryJson,
                    newGeometryJson = op.newGeometryJson, createdAt = op.createdAt,
                ),
            )

        override suspend fun getOperationsForWalk(walkId: Long): List<EditOperation> =
            dao.getOperationsForWalk(walkId).map {
                EditOperation(
                    it.id, it.walkId, it.operationOrder,
                    it.anchor1Lat, it.anchor1Lng, it.anchor2Lat, it.anchor2Lng,
                    it.waypointLat, it.waypointLng,
                    it.replacedGeometryJson, it.newGeometryJson, it.createdAt,
                )
            }

        override suspend fun deleteLastOperation(walkId: Long) = dao.deleteLastOperation(walkId)

        override suspend fun deleteAllOperationsForWalk(walkId: Long) = dao.deleteAllForWalk(walkId)
    }
