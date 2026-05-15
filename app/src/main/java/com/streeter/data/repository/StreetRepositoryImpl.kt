package com.streeter.data.repository

import com.streeter.data.local.dao.StreetDao
import com.streeter.data.local.entity.WalkSectionEntity
import com.streeter.data.local.entity.WalkStreetEntity
import com.streeter.data.local.mapper.*
import com.streeter.domain.model.*
import com.streeter.domain.repository.StreetRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class StreetRepositoryImpl
    @Inject
    constructor(
        private val dao: StreetDao,
    ) : StreetRepository {
        override suspend fun upsertStreet(street: Street): Long = dao.upsertStreet(street.toEntity())

        override suspend fun upsertStreets(streets: List<Street>): List<Long> = dao.upsertStreets(streets.map { it.toEntity() })

        override suspend fun getSectionsByStreetId(streetId: Long): List<StreetSection> =
            dao.getSectionsByStreetId(streetId).map { it.toDomain() }

        override suspend fun upsertSection(section: StreetSection) = dao.upsertSection(section.toEntity())

        override suspend fun upsertSections(sections: List<StreetSection>) = dao.upsertSections(sections.map { it.toEntity() })

        override suspend fun getSectionByStableId(stableId: String): StreetSection? = dao.getSectionByStableId(stableId)?.toDomain()

        override suspend fun getSectionsByStableIds(stableIds: List<String>): List<StreetSection> =
            dao.getSectionsByStableIds(stableIds).map { it.toDomain() }

        override suspend fun insertWalkStreetCoverage(coverage: WalkStreetCoverage) =
            dao.insertWalkStreet(
                WalkStreetEntity(
                    walkId = coverage.walkId,
                    streetId = coverage.streetId,
                    coveragePct = coverage.coveragePct,
                    walkedLengthM = coverage.walkedLengthM,
                ),
            )

        override suspend fun insertWalkStreetCoverages(coverages: List<WalkStreetCoverage>) =
            dao.insertWalkStreets(
                coverages.map { c ->
                    WalkStreetEntity(
                        walkId = c.walkId,
                        streetId = c.streetId,
                        coveragePct = c.coveragePct,
                        walkedLengthM = c.walkedLengthM,
                    )
                },
            )

        override suspend fun insertWalkSectionCoverage(coverage: WalkSectionCoverage) =
            dao.insertWalkSection(
                WalkSectionEntity(
                    walkId = coverage.walkId,
                    sectionStableId = coverage.sectionStableId,
                    coveredPct = coverage.coveredPct,
                ),
            )

        override suspend fun insertWalkSectionCoverages(coverages: List<WalkSectionCoverage>) =
            dao.insertWalkSections(
                coverages.map { c ->
                    WalkSectionEntity(
                        walkId = c.walkId,
                        sectionStableId = c.sectionStableId,
                        coveredPct = c.coveredPct,
                    )
                },
            )

        override suspend fun deleteWalkCoverageForWalk(walkId: Long) {
            dao.deleteWalkStreets(walkId)
            dao.deleteWalkSections(walkId)
        }

        override suspend fun getStreetCoverageForWalk(walkId: Long): List<WalkStreetCoverage> =
            dao.getWalkCoverage(walkId).map { it.toCoverage() }

        override suspend fun getStreetCountForWalk(walkId: Long): Int = dao.getStreetCountForWalk(walkId)

        override fun observeCoveredStreetCount(): Flow<Int> = dao.observeCoveredStreetCount()

        override fun observeTotalStreetCount(): Flow<Int> = dao.observeTotalStreetCount()

        override suspend fun getStreetById(streetId: Long): Street? = dao.getStreetById(streetId)?.toDomain()

        override suspend fun getCoveredLengthForStreet(streetId: Long): Double = dao.getCoveredLengthForStreet(streetId)

        override suspend fun getWalksForStreet(streetId: Long): List<StreetWalkEntry> =
            dao.getWalksForStreet(streetId).map { row ->
                StreetWalkEntry(
                    walkId = row.walkId,
                    walkDate = row.walkDate,
                    walkTitle = row.walkTitle,
                    walkedLengthM = row.walkedLengthM,
                    coveragePct = row.coveragePct,
                )
            }

        override suspend fun getCoveredSectionEdgeIdsForWalk(
            walkId: Long,
            streetId: Long,
        ): List<Long> = dao.getCoveredSectionEdgeIdsForWalk(walkId, streetId)
    }
