package com.streeter.data.local.dao

import androidx.room.*
import com.streeter.data.local.entity.EditOperationEntity

@Dao
interface EditOperationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(op: EditOperationEntity): Long

    @Query("SELECT * FROM edit_operations WHERE walkId = :walkId ORDER BY operationOrder ASC")
    suspend fun getOperationsForWalk(walkId: Long): List<EditOperationEntity>

    @Query("DELETE FROM edit_operations WHERE id = (SELECT MAX(id) FROM edit_operations WHERE walkId = :walkId)")
    suspend fun deleteLastOperation(walkId: Long)

    @Query("DELETE FROM edit_operations WHERE walkId = :walkId")
    suspend fun deleteAllForWalk(walkId: Long)
}
