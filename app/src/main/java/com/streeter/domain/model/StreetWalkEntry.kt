package com.streeter.domain.model

data class StreetWalkEntry(
    val walkId: Long,
    val walkDate: Long,
    val walkTitle: String?,
    val walkedLengthM: Double,
    val coveragePct: Float
)
