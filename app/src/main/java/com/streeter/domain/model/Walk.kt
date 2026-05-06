package com.streeter.domain.model

data class Walk(
    val id: Long = 0,
    val title: String?,
    val date: Long,
    val durationMs: Long,
    val distanceM: Double,
    val status: WalkStatus,
    val source: WalkSource,
    val createdAt: Long,
    val updatedAt: Long,
    val syncStatus: SyncStatus = SyncStatus.PENDING_SYNC,
    val serverWalkId: Long? = null,
)
