package com.streeter.domain.model

data class StreetSection(
    val id: Long = 0,
    val streetId: Long,
    val fromNodeOsmId: Long,
    val toNodeOsmId: Long,
    val lengthM: Double,
    val geometryJson: String,
    val stableId: String,
    val isOrphaned: Boolean
)
