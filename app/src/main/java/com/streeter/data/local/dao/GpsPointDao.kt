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
}
