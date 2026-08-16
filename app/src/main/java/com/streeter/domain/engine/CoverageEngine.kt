package com.streeter.domain.engine

/**
 * The second half of Calculation: turning a walk's matched ways into persisted Coverage.
 *
 * An interface so the decisions *around* the computation — which ways to hand it, and what a
 * walk with no ways at all means — can be exercised without the OSM graph behind the real
 * engine.
 */
interface CoverageEngine {
    /**
     * Compute and persist Coverage for [walkId] from its [matchedWayIds], reporting progress as
     * streets are processed.
     */
    suspend fun computeAndPersistCoverage(
        walkId: Long,
        matchedWayIds: List<Long>,
        onProgress: (suspend (processed: Int, total: Int) -> Unit)? = null,
    )
}
