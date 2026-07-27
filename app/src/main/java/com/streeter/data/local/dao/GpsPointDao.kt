package com.streeter.data.local.dao

import androidx.room.*
import com.streeter.data.local.entity.GpsPointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GpsPointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<GpsPointEntity>)

    @Query("SELECT * FROM gps_points WHERE walkId = :walkId AND isFiltered = 0 AND isManual = 0 ORDER BY timestamp ASC")
    suspend fun getPointsForMapMatching(walkId: Long): List<GpsPointEntity>

    @Query("SELECT * FROM gps_points WHERE walkId = :walkId AND isFiltered = 0 ORDER BY timestamp ASC")
    suspend fun getPointsForSync(walkId: Long): List<GpsPointEntity>

    @Query("SELECT * FROM gps_points WHERE walkId = :walkId ORDER BY timestamp ASC")
    fun observePoints(walkId: Long): Flow<List<GpsPointEntity>>

    @Query("DELETE FROM gps_points WHERE walkId = :walkId")
    suspend fun deleteByWalkId(walkId: Long)

    @Query("DELETE FROM gps_points WHERE id = :pointId AND walkId = :walkId")
    suspend fun deleteById(
        walkId: Long,
        pointId: Long,
    )

    @Query("SELECT COUNT(*) FROM gps_points WHERE walkId = :walkId")
    suspend fun countForWalk(walkId: Long): Int

    /** Deletes [pointId] and reports the remaining count atomically, so no writer can race between the two. */
    @Transaction
    suspend fun deleteByIdAndCountRemaining(
        walkId: Long,
        pointId: Long,
    ): Int {
        deleteById(walkId, pointId)
        return countForWalk(walkId)
    }
}
