package com.streeter.domain.repository

import com.streeter.domain.model.*
import kotlinx.coroutines.flow.Flow

interface StreetRepository {
    suspend fun upsertStreet(street: Street): Long
    suspend fun getSectionsByStreetId(streetId: Long): List<StreetSection>
    suspend fun upsertSection(section: StreetSection)
    suspend fun getSectionByStableId(stableId: String): StreetSection?
    suspend fun insertWalkStreetCoverage(coverage: WalkStreetCoverage)
    suspend fun insertWalkSectionCoverage(coverage: WalkSectionCoverage)
    suspend fun deleteWalkCoverageForWalk(walkId: Long)
    suspend fun getStreetCoverageForWalk(walkId: Long): List<WalkStreetCoverage>
    suspend fun getStreetCountForWalk(walkId: Long): Int
    fun observeCoveredStreetCount(): Flow<Int>
    fun observeTotalStreetCount(): Flow<Int>
}
