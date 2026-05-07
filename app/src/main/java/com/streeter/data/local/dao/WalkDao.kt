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

    @Query(
        """
        UPDATE walks
        SET title = :title, date = :date, durationMs = :durationMs, distanceM = :distanceM,
            status = :status, source = :source, createdAt = :createdAt, updatedAt = :updatedAt,
            syncStatus = :syncStatus, serverWalkId = :serverWalkId, lastPullSyncAt = :lastPullSyncAt
        WHERE id = :id
    """,
    )
    suspend fun update(
        id: Long,
        title: String?,
        date: Long,
        durationMs: Long,
        distanceM: Double,
        status: String,
        source: String,
        createdAt: Long,
        updatedAt: Long,
        syncStatus: String,
        serverWalkId: Long?,
        lastPullSyncAt: Long?,
    )

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

    @Query("SELECT gpsTraceSyncedAt FROM walks WHERE id = :id")
    suspend fun getGpsTraceSyncedAt(id: Long): Long?

    @Query("UPDATE walks SET gpsTraceSyncedAt = :timestamp WHERE id = :id")
    suspend fun updateGpsTraceSyncedAt(
        id: Long,
        timestamp: Long,
    )
}
