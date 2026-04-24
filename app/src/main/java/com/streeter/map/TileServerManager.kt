package com.streeter.map

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import timber.log.Timber
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages a loopback NanoHTTPD server that serves PMTiles from the app's assets.
 * MapLibre style JSON should point to http://127.0.0.1:{port}/tiles/{z}/{x}/{y}.pbf
 *
 * NOTE: For Phase 1 development, if no PMTiles file is bundled, the server returns
 * 404 for all tile requests — the MapLibre style will fall back gracefully.
 * Bundle assets/tiles/city.pmtiles before the production build.
 */
@Singleton
class TileServerManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private var server: TileServer? = null
        private val _port = AtomicInteger(0)

        val port: Int get() = _port.get()
        val baseUrl: String get() = "http://127.0.0.1:$port"

        fun start() {
            if (server != null) return
            val s = TileServer(context, 0) // 0 = OS assigns free port
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            _port.set(s.listeningPort)
            server = s
            Timber.d("TileServer started on port ${_port.get()}")
        }

        fun stop() {
            server?.stop()
            server = null
            Timber.d("TileServer stopped")
        }
    }

private class TileServer(
    private val context: Context,
    port: Int,
) : NanoHTTPD(port) {
    companion object {
        private const val PMTILES_ASSET = "tiles/city.pmtiles"
    }

    override fun serve(session: IHTTPSession): Response {
        val uri =
            session.uri ?: return newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found",
            )

        // Serve style.json
        if (uri == "/style.json") {
            return try {
                val styleJson = context.assets.open("tiles/style.json").bufferedReader().readText()
                newFixedLengthResponse(Response.Status.OK, "application/json", styleJson)
            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "style.json not found")
            }
        }

        // Serve tiles — simplified: serve raw asset bytes
        // In production, PMTiles range-request parsing would go here
        return try {
            val stream: InputStream = context.assets.open(PMTILES_ASSET)
            newChunkedResponse(Response.Status.OK, "application/x-protobuf", stream)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Tiles not available")
        }
    }
}
