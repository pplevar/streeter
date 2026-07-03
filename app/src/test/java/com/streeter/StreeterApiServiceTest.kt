package com.streeter

import com.streeter.data.remote.api.StreeterApiService
import com.streeter.di.configureStreeterClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transport-level spec for the delete + pull surface used by cross-device deletion (issue #22,
 * ADR-0003): `DELETE /walks/{id}` confirms on 2xx, and the Android pull always asks for tombstones.
 */
class StreeterApiServiceTest {
    private fun service(engine: MockEngine): StreeterApiService {
        val client = HttpClient(engine) { configureStreeterClient(tokenProvider = { "" }, debug = false) }
        return StreeterApiService(client, "http://localhost")
    }

    @Test
    fun `deleteWalk issues DELETE to the walk path and confirms on 204`() {
        var method: HttpMethod? = null
        var path: String? = null
        val engine =
            MockEngine { request ->
                method = request.method
                path = request.url.encodedPath
                respond("", HttpStatusCode.NoContent)
            }

        val confirmed = runBlocking { service(engine).deleteWalk(99L) }

        assertTrue(confirmed)
        assertEquals(HttpMethod.Delete, method)
        assertEquals("/api/streeter/walks/99", path)
    }

    @Test
    fun `deleteWalk does not confirm on a server error`() {
        val engine = MockEngine { respond("boom", HttpStatusCode.InternalServerError) }

        assertFalse(runBlocking { service(engine).deleteWalk(99L) })
    }

    @Test
    fun `getWalks asks the server to include tombstones`() {
        var includeDeleted: String? = null
        val engine =
            MockEngine { request ->
                includeDeleted = request.url.parameters["includeDeleted"]
                respond("[]", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }

        runBlocking { service(engine).getWalks(since = 0L, limit = 100, offset = 0) }

        assertEquals("true", includeDeleted)
    }
}
