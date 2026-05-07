package com.streeter.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class GpsTraceResponse(
    val walkId: Long,
    val pointCount: Int,
    val points: List<GpsPointDto>,
    val updatedAt: Long,
)
