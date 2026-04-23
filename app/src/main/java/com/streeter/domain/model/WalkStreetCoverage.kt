package com.streeter.domain.model

data class WalkStreetCoverage(
    val id: Long = 0,
    val walkId: Long,
    val streetId: Long,
    val streetName: String,
    val coveragePct: Float,
    val walkedLengthM: Double = 0.0
)
