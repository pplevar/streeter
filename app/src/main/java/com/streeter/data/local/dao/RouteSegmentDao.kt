package com.streeter.data.local.dao

import androidx.room.*
import com.streeter.data.local.entity.RouteSegmentEntity

@Dao
interface RouteSegmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(segment: RouteSegmentEntity): Long

    @Query("SELECT * FROM route_segments WHERE walkId = :walkId ORDER BY segmentOrder ASC")
    suspend fun getSegmentsForWalk(walkId: Long): List<RouteSegmentEntity>

    @Query("DELETE FROM route_segments WHERE walkId = :walkId")
    suspend fun deleteForWalk(walkId: Long)
}
