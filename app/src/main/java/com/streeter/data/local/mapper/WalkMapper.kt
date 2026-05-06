package com.streeter.data.local.mapper

import com.streeter.data.local.entity.WalkEntity
import com.streeter.domain.model.SyncStatus
import com.streeter.domain.model.Walk
import com.streeter.domain.model.WalkSource
import com.streeter.domain.model.WalkStatus

fun WalkEntity.toDomain() =
    Walk(
        id = id,
        title = title,
        date = date,
        durationMs = durationMs,
        distanceM = distanceM,
        status = WalkStatus.valueOf(status),
        source = WalkSource.valueOf(source),
        createdAt = createdAt,
        updatedAt = updatedAt,
        syncStatus = SyncStatus.valueOf(syncStatus),
        serverWalkId = serverWalkId,
    )

fun Walk.toEntity() =
    WalkEntity(
        id = id,
        title = title,
        date = date,
        durationMs = durationMs,
        distanceM = distanceM,
        status = status.name,
        source = source.name,
        createdAt = createdAt,
        updatedAt = updatedAt,
        syncStatus = syncStatus.name,
        serverWalkId = serverWalkId,
    )
