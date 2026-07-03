package com.streeter

import com.streeter.di.configureStreeterClient
import com.streeter.domain.sync.SyncAuthException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Locks the sync auth-failure boundary (issue #19): a 401 from the server is a non-transient auth
 * failure surfaced as a typed [SyncAuthException], while other statuses keep their existing
 * behaviour so a transient 5xx is not mistaken for a bad token. See ADR-0002.
 */
class SyncAuthFailureTest {
    private fun client(engine: MockEngine): HttpClient =
        HttpClient(engine) { configureStreeterClient(tokenProvider = { "t" }, debug = false) }

    @Test
    fun `a 401 response surfaces as a SyncAuthException`() {
        val engine = MockEngine { respond("unauthorized", HttpStatusCode.Unauthorized) }
        val client = client(engine)

        assertThrows(SyncAuthException::class.java) {
            runBlocking { client.get("http://localhost/api/streeter/walks") }
        }
    }

    @Test
    fun `a successful response does not throw`() {
        val engine = MockEngine { respondOk() }
        val client = client(engine)

        runBlocking { client.get("http://localhost/api/streeter/walks") }
    }
}
