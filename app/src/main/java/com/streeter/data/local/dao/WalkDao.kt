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

    @Query("SELECT * FROM walks WHERE id = :id")
    fun observeById(id: Long): Flow<WalkEntity?>

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

    @Query("SELECT * FROM walks WHERE syncStatus = 'PENDING_SYNC' AND status = 'COMPLETED'")
    suspend fun getPendingSync(): List<WalkEntity>

    @Query("UPDATE walks SET syncStatus = :syncStatus, serverWalkId = :serverWalkId WHERE id = :id")
    suspend fun updateSyncStatus(
        id: Long,
        syncStatus: String,
        serverWalkId: Long?,
    )

    @Query("SELECT * FROM walks WHERE serverWalkId = :serverWalkId LIMIT 1")
    suspend fun getWalkByServerWalkId(serverWalkId: Long): WalkEntity?

    @Query("SELECT MAX(lastPullSyncAt) FROM walks")
    suspend fun getLastPullSyncAt(): Long?

    @Query("UPDATE walks SET lastPullSyncAt = :timestamp WHERE id = :id")
    suspend fun updateLastPullSyncAt(
        id: Long,
        timestamp: Long,
    )
}
