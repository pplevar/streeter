package com.streeter.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class WalkSyncRequest(
    val clientId: String,
    val localWalkId: Long,
    val title: String?,
    val date: Long,
    val durationMs: Long,
    val distanceM: Double,
    val status: String,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
)
