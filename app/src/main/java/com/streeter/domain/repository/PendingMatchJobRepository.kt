package com.streeter.domain.repository

import com.streeter.domain.model.PendingMatchJob

interface PendingMatchJobRepository {
    suspend fun enqueue(job: PendingMatchJob): Long
    suspend fun getJobForWalk(walkId: Long): PendingMatchJob?
    suspend fun updateJob(job: PendingMatchJob)
    suspend fun deleteJobForWalk(walkId: Long)
}
