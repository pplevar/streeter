package com.streeter.data.repository

import com.streeter.data.local.dao.PendingMatchJobDao
import com.streeter.data.local.entity.PendingMatchJobEntity
import com.streeter.domain.model.JobStatus
import com.streeter.domain.model.PendingMatchJob
import com.streeter.domain.repository.PendingMatchJobRepository
import javax.inject.Inject

class PendingMatchJobRepositoryImpl
    @Inject
    constructor(
        private val dao: PendingMatchJobDao,
    ) : PendingMatchJobRepository {
        override suspend fun enqueue(job: PendingMatchJob): Long = dao.insert(job.toEntity())

        override suspend fun getJobForWalk(walkId: Long): PendingMatchJob? = dao.getJobForWalk(walkId)?.toDomain()

        override suspend fun updateJob(job: PendingMatchJob) = dao.update(job.toEntity())

        override suspend fun deleteJobForWalk(walkId: Long) = dao.deleteForWalk(walkId)

        private fun PendingMatchJob.toEntity() =
            PendingMatchJobEntity(
                id = id,
                walkId = walkId,
                queuedAt = queuedAt,
                status = status.name,
                retryCount = retryCount,
                lastError = lastError,
            )

        private fun PendingMatchJobEntity.toDomain() =
            PendingMatchJob(
                id = id,
                walkId = walkId,
                queuedAt = queuedAt,
                status = JobStatus.valueOf(status),
                retryCount = retryCount,
                lastError = lastError,
            )
    }
