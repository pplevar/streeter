package com.streeter.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class WalkSyncResponse(
    val serverWalkId: Long,
)
