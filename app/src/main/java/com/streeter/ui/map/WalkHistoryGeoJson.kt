package com.streeter.ui.map

import com.streeter.domain.model.GpsPoint

/** An empty GeoJSON payload — a valid source value that draws nothing. */
const val EMPTY_FEATURE_COLLECTION = """{"type":"FeatureCollection","features":[]}"""

/**
 * Turns raw GPS Traces of several walks into one `LineString` per walk.
 *
 * One feature per walk, never one line across walks: joining them would draw a spurious
 * straight segment from the end of one walk to the start of the next. Filtered points are
 * dropped, and a walk left with fewer than two points contributes no feature.
 *
 * Pure and Android-free so it can run off the main thread and be tested directly.
 */
fun buildWalkHistoryGeoJson(points: List<GpsPoint>): String {
    val features =
        points
            .asSequence()
            .filter { !it.isFiltered }
            .groupBy { it.walkId }
            .values
            .map { walkPoints -> walkPoints.sortedBy { it.timestamp } }
            .filter { it.size >= 2 }
            .joinToString(",") { walkPoints ->
                val coords = walkPoints.joinToString(",") { "[${it.lng},${it.lat}]" }
                """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]},"properties":{}}"""
            }
    return """{"type":"FeatureCollection","features":[$features]}"""
}
