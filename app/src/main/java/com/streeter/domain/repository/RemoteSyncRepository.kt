package com.streeter.domain.repository

interface RemoteSyncRepository {
    suspend fun syncWalk(walkId: Long): Result<Unit>

    /**
     * Pull every walk the server has seen since this device's cursor, page by page, and advance
     * the cursor past what landed. The cursor lives behind `SyncCursor`, so callers neither pass
     * nor persist it.
     */
    suspend fun pullWalks(): Result<Unit>

    /**
     * Dispatch a tombstoned walk's deletion to the server (`DELETE /walks/{serverWalkId}`) and, on
     * confirmation, hard-delete the local row. Idempotent: an already-gone or never-synced walk
     * succeeds without a server call.
     */
    suspend fun deleteWalk(walkId: Long): Result<Unit>
}
