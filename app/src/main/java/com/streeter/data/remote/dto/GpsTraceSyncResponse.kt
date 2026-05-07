package com.streeter.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class GpsTraceSyncResponse(
    val accepted: Boolean,
    val updatedAt: Long,
)
