package com.streeter.ui.map

import com.streeter.domain.repository.GpsPointRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Loads every past walk's raw GPS Trace as map-ready GeoJSON.
 *
 * A seam of its own rather than logic inside `RecordingViewModel`: the view model needs an
 * Android `Context` for the recording service, so this is the part that can be tested with
 * plain fakes.
 */
class WalkHistoryLoader
    @Inject
    constructor(
        private val gpsPointRepository: GpsPointRepository,
    ) {
        /**
         * History excluding [excludeWalkId] — the walk in progress, which the live route layer
         * already draws. Pass a non-existent id (e.g. `-1`) when no walk is in progress.
         */
        suspend fun load(excludeWalkId: Long): String {
            val points = gpsPointRepository.getPointsExcludingWalk(excludeWalkId)
            return withContext(Dispatchers.Default) { buildWalkHistoryGeoJson(points) }
        }
    }
