package com.streeter.domain.model

data class WalkSectionCoverage(
    val id: Long = 0,
    val walkId: Long,
    val sectionStableId: String,
    val coveredPct: Float,
)
