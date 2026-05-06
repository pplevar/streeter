package com.streeter.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class GpsTraceSyncRequest(
    val points: List<GpsPointDto>,
)
