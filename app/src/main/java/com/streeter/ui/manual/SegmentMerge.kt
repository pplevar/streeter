package com.streeter.ui.manual

import com.streeter.domain.geometry.TraceGeometry

/**
 * Joining a manually created walk's routed segments into the one route it stores.
 *
 * A manual walk is built a segment at a time, each routed from the previous segment's end, so
 * consecutive segments repeat the junction between them. On [TraceGeometry] this reads every
 * shape the routing engine returns — including the multi-line routes the previous merge quietly
 * dropped — and is testable off a device.
 */
object SegmentMerge {
    /**
     * [geometries] as a single `LineString` feature, with the junction each segment repeats from
     * its predecessor dropped once.
     *
     * One line, not a collection of them: a manual walk is contiguous by construction — every
     * segment is routed from where the last one ended — and the rest of the app still reads a
     * walk's route as a single `Feature` (bounds fitting and the matched-distance sum both do),
     * so a collection here would not be understood until those callers move too (issue #58).
     *
     * A malformed segment throws [com.streeter.domain.geometry.MalformedGeometryException]: half a
     * manual walk is not a walk anyone drew, and the caller already turns the failure into a
     * "could not save" message.
     */
    fun merge(geometries: List<String>): String {
        val points =
            geometries.flatMapIndexed { index, geometry ->
                val segment = TraceGeometry.parse(geometry)
                // The junction is the previous segment's last point, already accumulated.
                if (index == 0 || segment.isEmpty()) segment else segment.drop(1)
            }
        return if (points.isEmpty()) TraceGeometry.EMPTY_FEATURE_COLLECTION else TraceGeometry.lineStringFeature(points)
    }
}
