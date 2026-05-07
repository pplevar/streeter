package com.streeter.domain.repository

interface RemoteSyncRepository {
    suspend fun syncWalk(walkId: Long): Result<Unit>

    suspend fun pullWalks(since: Long): Result<Unit>
}
