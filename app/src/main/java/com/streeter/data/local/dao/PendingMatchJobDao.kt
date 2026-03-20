package com.streeter.data.local.dao

import androidx.room.*
import com.streeter.data.local.entity.PendingMatchJobEntity

@Dao
interface PendingMatchJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(job: PendingMatchJobEntity): Long

    @Query("SELECT * FROM pending_match_jobs WHERE walkId = :walkId LIMIT 1")
    suspend fun getJobForWalk(walkId: Long): PendingMatchJobEntity?

    @Update
    suspend fun update(job: PendingMatchJobEntity)

    @Query("DELETE FROM pending_match_jobs WHERE walkId = :walkId")
    suspend fun deleteForWalk(walkId: Long)
}
