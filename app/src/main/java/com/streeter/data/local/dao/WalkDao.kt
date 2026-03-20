package com.streeter.data.local.dao

import androidx.room.*
import com.streeter.data.local.entity.WalkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WalkDao {
    @Query("SELECT * FROM walks WHERE status != 'DELETED' ORDER BY date DESC")
    fun getAllWalks(): Flow<List<WalkEntity>>

    @Query("SELECT * FROM walks WHERE id = :id")
    suspend fun getById(id: Long): WalkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(walk: WalkEntity): Long

    @Update
    suspend fun update(walk: WalkEntity)

    @Query("UPDATE walks SET status = 'DELETED' WHERE id = :id")
    suspend fun softDelete(id: Long)

    @Query("DELETE FROM walks WHERE id = :id")
    suspend fun hardDelete(id: Long)

    @Query("SELECT * FROM walks WHERE status = 'RECORDING' LIMIT 1")
    suspend fun getActiveRecording(): WalkEntity?
}
