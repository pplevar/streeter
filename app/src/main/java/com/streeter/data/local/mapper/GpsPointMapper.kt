package com.streeter.data.local.mapper

import com.streeter.data.local.entity.GpsPointEntity
import com.streeter.domain.model.GpsPoint

fun GpsPointEntity.toDomain() =
    GpsPoint(
        id = id,
        walkId = walkId,
        lat = lat,
        lng = lng,
        timestamp = timestamp,
        accuracyM = accuracyM,
        speedKmh = speedKmh,
        isFiltered = isFiltered,
    )

fun GpsPoint.toEntity() =
    GpsPointEntity(
        id = id,
        walkId = walkId,
        lat = lat,
        lng = lng,
        timestamp = timestamp,
        accuracyM = accuracyM,
        speedKmh = speedKmh,
        isFiltered = isFiltered,
    )
