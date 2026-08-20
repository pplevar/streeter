package com.streeter.data.local.dao

import androidx.room.*
import com.streeter.data.local.entity.GpsPointEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GpsPointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<GpsPointEntity>)

    @Query("SELECT * FROM gps_points WHERE walkId = :walkId AND isFiltered = 0 AND isManual = 0 ORDER BY timestamp ASC")
    suspend fun getPointsForMapMatching(walkId: Long): List<GpsPointEntity>

    @Query("SELECT * FROM gps_points WHERE walkId = :walkId AND isFiltered = 0 ORDER BY timestamp ASC")
    suspend fun getPointsForSync(walkId: Long): List<GpsPointEntity>

    /**
     * A walk's usable points — what the points editor lists and draws. Outlier-filtered points
     * are excluded: they already count for neither Calculation nor sync, so showing them would
     * put spikes on the map that no other screen draws and that deleting changes nothing about.
     */
    @Query("SELECT * FROM gps_points WHERE walkId = :walkId AND isFiltered = 0 ORDER BY timestamp ASC")
    fun observePoints(walkId: Long): Flow<List<GpsPointEntity>>

    /**
     * Every walk's points except [excludeWalkId]'s — the recording screen's history layer.
     * Grouped by walk so each walk can be drawn as its own LineString.
     *
     * Joins `walks` to skip `DELETED` tombstones: a synced walk the user deleted keeps its row
     * (and its points, via no cascade) until the server confirms the delete, which offline can
     * be days. Its trace must disappear from the map the moment it is deleted, not the moment
     * the delete is dispatched.
     */
    @Query(
        "SELECT p.* FROM gps_points p INNER JOIN walks w ON w.id = p.walkId " +
            "WHERE p.walkId != :excludeWalkId AND p.isFiltered = 0 AND w.status != 'DELETED' " +
            "ORDER BY p.walkId ASC, p.timestamp ASC",
    )
    suspend fun getPointsExcludingWalk(excludeWalkId: Long): List<GpsPointEntity>

    /**
     * Moves one point to a new coordinate, scoped by walk exactly as [deleteById] is.
     *
     * Only `lat`/`lng` change: a repositioned observation keeps its id, its timestamp, its
     * accuracy and its place in the order, and nothing in the row records that it was moved
     * (see CONTEXT.md, **GPS Trace**).
     */
    @Query("UPDATE gps_points SET lat = :lat, lng = :lng WHERE id = :pointId AND walkId = :walkId")
    suspend fun updateCoordinate(
        walkId: Long,
        pointId: Long,
        lat: Double,
        lng: Double,
    )

    @Query("DELETE FROM gps_points WHERE walkId = :walkId")
    suspend fun deleteByWalkId(walkId: Long)

    @Query("DELETE FROM gps_points WHERE id = :pointId AND walkId = :walkId")
    suspend fun deleteById(
        walkId: Long,
        pointId: Long,
    )

    /**
     * A walk's usable point count — outlier-filtered points are excluded. This is the set
     * Calculation consumes and the set the points editor shows, so the minimum-points floor
     * is computed over the same points the user can see and act on.
     */
    @Query("SELECT COUNT(*) FROM gps_points WHERE walkId = :walkId AND isFiltered = 0")
    suspend fun countUnfilteredForWalk(walkId: Long): Int

    /** Deletes [pointId] and reports the remaining count atomically, so no writer can race between the two. */
    @Transaction
    suspend fun deleteByIdAndCountRemaining(
        walkId: Long,
        pointId: Long,
    ): Int {
        deleteById(walkId, pointId)
        return countUnfilteredForWalk(walkId)
    }
}
