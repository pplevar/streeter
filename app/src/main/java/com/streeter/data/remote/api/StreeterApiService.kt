package com.streeter.data.remote.api

import com.streeter.data.remote.dto.GpsTraceSyncRequest
import com.streeter.data.remote.dto.WalkSyncDto
import com.streeter.data.remote.dto.WalkSyncRequest
import com.streeter.data.remote.dto.WalkSyncResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

class StreeterApiService(private val client: HttpClient, private val baseUrl: String) {
    suspend fun syncWalk(request: WalkSyncRequest): WalkSyncResponse =
        client.post("$baseUrl/api/streeter/walks") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    suspend fun syncGpsTrace(
        serverWalkId: Long,
        request: GpsTraceSyncRequest,
    ) {
        client.post("$baseUrl/api/streeter/walks/$serverWalkId/gps-trace") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun getWalks(
        since: Long,
        limit: Int,
        offset: Int,
    ): List<WalkSyncDto> =
        client.get("$baseUrl/api/streeter/walks") {
            parameter("since", since)
            parameter("limit", limit)
            parameter("offset", offset)
        }.body()
}
