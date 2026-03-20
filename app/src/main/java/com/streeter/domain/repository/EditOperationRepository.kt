package com.streeter.domain.repository

import com.streeter.domain.model.EditOperation

interface EditOperationRepository {
    suspend fun insertOperation(op: EditOperation): Long
    suspend fun getOperationsForWalk(walkId: Long): List<EditOperation>
    suspend fun deleteLastOperation(walkId: Long)
    suspend fun deleteAllOperationsForWalk(walkId: Long)
}
