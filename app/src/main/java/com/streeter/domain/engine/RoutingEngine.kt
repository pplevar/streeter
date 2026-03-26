package com.streeter.domain.engine

import com.streeter.domain.model.GpsPoint
import com.streeter.domain.model.LatLng
import com.streeter.domain.model.MatchResult
import com.streeter.domain.model.RouteResult

interface RoutingEngine {
    /**
     * Returns true when the engine is initialized and ready to accept requests.
     * On first launch, GraphHopper graph preparation may take 10–60 seconds.
     */
    suspend fun isReady(): Boolean

    /**
     * Initialize the engine. Suspends until ready. Call once at app start.
     */
    suspend fun initialize()

    /**
     * Map-match a GPS trace to the street network.
     * Returns a [MatchResult] with snapped points and matched OSM way IDs.
     */
    suspend fun matchTrace(points: List<GpsPoint>): Result<MatchResult>

    /**
     * Calculate the shortest walkable route from [from] to [to],
     * optionally passing through [via] waypoints in order.
     */
    suspend fun route(from: LatLng, to: LatLng, via: List<LatLng> = emptyList()): Result<RouteResult>

    /**
     * Look up the street name for a given GraphHopper edge ID.
     * Returns null if the engine is not initialized or the edge has no name.
     */
    fun getStreetName(edgeId: Long): String?
}
