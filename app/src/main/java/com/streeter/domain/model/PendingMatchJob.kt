package com.streeter.domain.model

enum class JobStatus { QUEUED, IN_PROGRESS, DONE, FAILED }

data class PendingMatchJob(
    val id: Long = 0,
    val walkId: Long,
    val queuedAt: Long,
    val status: JobStatus,
    val retryCount: Int,
    val lastError: String?,
)
