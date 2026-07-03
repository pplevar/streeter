package com.streeter.data.remote.api

import com.streeter.data.remote.dto.GpsTraceResponse
import com.streeter.data.remote.dto.GpsTraceSyncRequest
import com.streeter.data.remote.dto.GpsTraceSyncResponse
import com.streeter.data.remote.dto.WalkSyncDto
import com.streeter.data.remote.dto.WalkSyncRequest
import com.streeter.data.remote.dto.WalkSyncResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

class StreeterApiService(private val client: HttpClient, private val baseUrl: String) {
    suspend fun syncWalk(request: WalkSyncRequest): WalkSyncResponse =
        client.post("$baseUrl/api/streeter/walks") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun syncGpsTrace(
        serverWalkId: Long,
        request: GpsTraceSyncRequest,
    ): GpsTraceSyncResponse =
        client.post("$baseUrl/api/streeter/walks/$serverWalkId/gps-trace") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun getGpsTrace(serverWalkId: Long): GpsTraceResponse = client.get("$baseUrl/api/streeter/walks/$serverWalkId/gps-trace").body()

    suspend fun getWalks(
        since: Long,
        limit: Int,
        offset: Int,
    ): List<WalkSyncDto> =
        client.get("$baseUrl/api/streeter/walks") {
            parameter("since", since)
            parameter("limit", limit)
            parameter("offset", offset)
            // The Android client is the one consumer that needs tombstones so deletions converge
            // across devices (ADR-0003).
            parameter("includeDeleted", true)
        }.body()

    /**
     * Delete a synced walk on the server. Returns `true` when the server confirms (2xx, typically
     * `204`); the delete is idempotent server-side, so deleting an already-deleted or unknown walk
     * still confirms.
     */
    suspend fun deleteWalk(serverWalkId: Long): Boolean = client.delete("$baseUrl/api/streeter/walks/$serverWalkId").status.isSuccess()
}
