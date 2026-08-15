package com.streeter.domain.repository

import com.streeter.domain.model.GpsPoint
import kotlinx.coroutines.flow.Flow

interface GpsPointRepository {
    suspend fun insertPoints(points: List<GpsPoint>)

    suspend fun getPointsForWalk(walkId: Long): List<GpsPoint>

    suspend fun getPointsForMapMatching(walkId: Long): List<GpsPoint>

    /**
     * A walk's points excluding Outlier Points, oldest first — what the points editor lists,
     * draws, and steps through. Exactly the set Calculation consumes.
     */
    fun observePointsForWalk(walkId: Long): Flow<List<GpsPoint>>

    /**
     * All unfiltered points of every non-deleted walk except [excludeWalkId], ordered by walk
     * then time. Backs the recording map's history layer; pass a non-existent id to exclude
     * nothing.
     */
    suspend fun getPointsExcludingWalk(excludeWalkId: Long): List<GpsPoint>

    suspend fun replacePointsFromRemote(
        walkId: Long,
        points: List<GpsPoint>,
    )

    /**
     * Deletes [pointId] from [walkId] and returns the walk's remaining point count, counting
     * only non-Outlier points — the same set [observePointsForWalk] emits.
     */
    suspend fun deletePoint(
        walkId: Long,
        pointId: Long,
    ): Int
}
