package com.streeter.data.local.dao

import androidx.room.*
import com.streeter.data.local.entity.StreetEntity
import com.streeter.data.local.entity.StreetSectionEntity
import com.streeter.data.local.entity.WalkSectionEntity
import com.streeter.data.local.entity.WalkStreetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StreetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStreet(street: StreetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStreets(streets: List<StreetEntity>): List<Long>

    @Query("SELECT * FROM streets WHERE osmWayId = :osmWayId")
    suspend fun getStreetByOsmWayId(osmWayId: Long): StreetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSection(section: StreetSectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSections(sections: List<StreetSectionEntity>)

    @Query("SELECT * FROM street_sections WHERE streetId = :streetId")
    suspend fun getSectionsByStreetId(streetId: Long): List<StreetSectionEntity>

    @Query("SELECT * FROM street_sections WHERE stableId = :stableId")
    suspend fun getSectionByStableId(stableId: String): StreetSectionEntity?

    @Query("SELECT * FROM street_sections WHERE stableId IN (:stableIds)")
    suspend fun getSectionsByStableIds(stableIds: List<String>): List<StreetSectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWalkStreet(coverage: WalkStreetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWalkStreets(coverages: List<WalkStreetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWalkSection(coverage: WalkSectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWalkSections(coverages: List<WalkSectionEntity>)

    @Query("DELETE FROM walk_streets WHERE walkId = :walkId")
    suspend fun deleteWalkStreets(walkId: Long)

    @Query("DELETE FROM walk_sections WHERE walkId = :walkId")
    suspend fun deleteWalkSections(walkId: Long)

    @Query(
        """
        SELECT ws.id, ws.walkId, ws.streetId, s.name as streetName,
               ws.coveragePct, ws.walkedLengthM
        FROM walk_streets ws
        INNER JOIN streets s ON ws.streetId = s.id
        WHERE ws.walkId = :walkId
        ORDER BY ws.coveragePct DESC
    """,
    )
    fun observeWalkCoverage(walkId: Long): Flow<List<WalkStreetWithName>>

    @Query(
        """
        SELECT ws.id, ws.walkId, ws.streetId, s.name as streetName,
               ws.coveragePct, ws.walkedLengthM
        FROM walk_streets ws
        INNER JOIN streets s ON ws.streetId = s.id
        WHERE ws.walkId = :walkId
        ORDER BY ws.coveragePct DESC
    """,
    )
    suspend fun getWalkCoverage(walkId: Long): List<WalkStreetWithName>

    @Query("SELECT COUNT(*) FROM walk_streets WHERE walkId = :walkId")
    suspend fun getStreetCountForWalk(walkId: Long): Int

    @Query("SELECT COUNT(DISTINCT streetId) FROM walk_streets")
    fun observeCoveredStreetCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM streets")
    fun observeTotalStreetCount(): Flow<Int>

    @Query("SELECT * FROM streets WHERE id = :streetId")
    suspend fun getStreetById(streetId: Long): StreetEntity?

    @Query("SELECT COALESCE(SUM(lengthM), 0.0) FROM street_sections WHERE streetId = :streetId")
    suspend fun getCoveredLengthForStreet(streetId: Long): Double

    @Query(
        """
        SELECT ws.walkId, w.date AS walkDate, w.title AS walkTitle,
               ws.walkedLengthM, ws.coveragePct
        FROM walk_streets ws
        INNER JOIN walks w ON ws.walkId = w.id
        WHERE ws.streetId = :streetId AND w.status = 'COMPLETED'
        ORDER BY w.date DESC
    """,
    )
    suspend fun getWalksForStreet(streetId: Long): List<StreetWalkRow>

    @Query(
        """
        SELECT ss.fromNodeOsmId
        FROM street_sections ss
        INNER JOIN walk_sections ws ON ss.stableId = ws.sectionStableId
        WHERE ss.streetId = :streetId AND ws.walkId = :walkId
    """,
    )
    suspend fun getCoveredSectionEdgeIdsForWalk(
        walkId: Long,
        streetId: Long,
    ): List<Long>
}

data class StreetWalkRow(
    val walkId: Long,
    val walkDate: Long,
    val walkTitle: String?,
    val walkedLengthM: Double,
    val coveragePct: Float,
)

data class WalkStreetWithName(
    val id: Long,
    val walkId: Long,
    val streetId: Long,
    val streetName: String,
    val coveragePct: Float,
    val walkedLengthM: Double,
)
