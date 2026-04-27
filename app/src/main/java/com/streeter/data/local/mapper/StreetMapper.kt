package com.streeter.data.local.mapper

import com.streeter.data.local.dao.WalkStreetWithName
import com.streeter.data.local.entity.*
import com.streeter.domain.model.*

fun StreetEntity.toDomain() =
    Street(
        id = id,
        osmWayId = osmWayId,
        name = name,
        cityTotalLengthM = cityTotalLengthM,
        osmDataVersion = osmDataVersion,
        osmNameHash = osmNameHash,
    )

fun Street.toEntity() =
    StreetEntity(
        id = id,
        osmWayId = osmWayId,
        name = name,
        cityTotalLengthM = cityTotalLengthM,
        osmDataVersion = osmDataVersion,
        osmNameHash = osmNameHash,
    )

fun StreetSectionEntity.toDomain() =
    StreetSection(
        id = id,
        streetId = streetId,
        fromNodeOsmId = fromNodeOsmId,
        toNodeOsmId = toNodeOsmId,
        lengthM = lengthM,
        geometryJson = geometryJson,
        stableId = stableId,
        isOrphaned = isOrphaned,
    )

fun StreetSection.toEntity() =
    StreetSectionEntity(
        id = id,
        streetId = streetId,
        fromNodeOsmId = fromNodeOsmId,
        toNodeOsmId = toNodeOsmId,
        lengthM = lengthM,
        geometryJson = geometryJson,
        stableId = stableId,
        isOrphaned = isOrphaned,
    )

fun WalkStreetWithName.toCoverage() =
    WalkStreetCoverage(
        id = id,
        walkId = walkId,
        streetId = streetId,
        streetName = streetName,
        coveragePct = coveragePct,
        walkedLengthM = walkedLengthM,
    )
