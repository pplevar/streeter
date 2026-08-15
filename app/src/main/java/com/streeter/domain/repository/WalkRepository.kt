package com.streeter.domain.repository

import com.streeter.data.remote.dto.WalkSyncDto
import com.streeter.domain.model.*
import kotlinx.coroutines.flow.Flow

interface WalkRepository {
    fun getAllWalks(): Flow<List<Walk>>

    fun observeWalk(id: Long): Flow<Walk?>

    suspend fun getWalkById(id: Long): Walk?

    suspend fun insertWalk(walk: Walk): Long

    suspend fun updateWalk(walk: Walk)

    /** Mark a walk as a `DELETED` tombstone, keeping the row (and its `serverWalkId`) for dispatch. */
    suspend fun markWalkDeleted(id: Long)

    /** Remove a walk's row entirely; Room CASCADE drops its Coverage and GPS trace. */
    suspend fun hardDeleteWalk(id: Long)

    suspend fun getActiveRecordingWalk(): Walk?

    fun getWalkWithCoverage(walkId: Long): Flow<List<WalkStreetCoverage>>

    suspend fun getWalksPendingSync(): List<Walk>

    /** Set a walk's sync status; writes [serverWalkId] unconditionally, `null` included. */
    suspend fun updateSyncStatus(
        id: Long,
        syncStatus: SyncStatus,
        serverWalkId: Long?,
    )

    /**
     * Mark a sync attempt as failed, leaving the walk's `serverWalkId` untouched so an
     * already-synced walk keeps its server identity for the retry (issue #52).
     */
    suspend fun markSyncFailed(id: Long)

    suspend fun getWalkByServerWalkId(serverWalkId: Long): Walk?

    suspend fun getLastPullSyncAt(): Long?

    suspend fun upsertFromRemote(dto: WalkSyncDto)

    suspend fun updateLastPullSyncAt(
        id: Long,
        timestamp: Long,
    )

    suspend fun getGpsTraceSyncedAt(id: Long): Long?

    suspend fun updateGpsTraceSyncedAt(
        id: Long,
        timestamp: Long,
    )
}
