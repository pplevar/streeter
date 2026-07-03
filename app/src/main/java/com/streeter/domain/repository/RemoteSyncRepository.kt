package com.streeter.domain.repository

interface RemoteSyncRepository {
    suspend fun syncWalk(walkId: Long): Result<Unit>

    suspend fun pullWalks(since: Long): Result<Unit>

    /**
     * Dispatch a tombstoned walk's deletion to the server (`DELETE /walks/{serverWalkId}`) and, on
     * confirmation, hard-delete the local row. Idempotent: an already-gone or never-synced walk
     * succeeds without a server call.
     */
    suspend fun deleteWalk(walkId: Long): Result<Unit>
}
