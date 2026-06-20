package com.streeter

import com.streeter.di.configureStreeterClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the sync auth transport gate (issue #17): the Ktor client attaches a shared bearer token
 * on every request when one is configured, and omits the header entirely when it is blank so the
 * server's PERMISSIVE/STRICT policy decides. See ADR-0002.
 */
class SyncAuthHeaderTest {
    private fun capturedAuthHeader(token: String): String? {
        var captured: String? = null
        val engine =
            MockEngine { request ->
                captured = request.headers[HttpHeaders.Authorization]
                respondOk()
            }
        val client = HttpClient(engine) { configureStreeterClient(tokenProvider = { token }, debug = false) }
        runBlocking { client.get("http://localhost/api/streeter/walks") }
        return captured
    }

    @Test
    fun `attaches bearer token when token is non-blank`() {
        assertEquals("Bearer my-token", capturedAuthHeader("my-token"))
    }

    @Test
    fun `omits authorization header when token is empty`() {
        assertEquals(null, capturedAuthHeader(""))
    }

    @Test
    fun `omits authorization header when token is whitespace only`() {
        assertEquals(null, capturedAuthHeader("   "))
    }
}
