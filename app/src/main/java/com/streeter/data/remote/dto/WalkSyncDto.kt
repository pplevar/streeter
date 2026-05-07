package com.streeter.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class WalkSyncDto(
    val serverWalkId: Long,
    val localWalkId: Long,
    val clientId: String,
    val title: String?,
    val date: Long,
    val durationMs: Long,
    val distanceM: Double,
    val status: String,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
    val serverUpdatedAt: Long,
)
