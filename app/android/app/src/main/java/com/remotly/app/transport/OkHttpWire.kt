package com.remotly.app.transport

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

// The production Wire: a binary WebSocket to the daemon, one Remotly message
// per WebSocket message. OkHttp answers the daemon's liveness pings on its
// own; the periodic app-side ping keeps idle connections alive.
class OkHttpWire(
    private val host: String,
    private val port: Int,
) : Wire {
    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null

    override var listener: WireListener = NOOP

    override fun connect() {
        val authority = if (host.contains(":")) "[$host]" else host
        val url = if (port == 80) "http://$authority/" else "http://$authority:$port/"
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                listener.onBinary(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // The protocol is binary-only; a text frame is a violation.
                listener.onFailure("text frame")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val detail = t.message
                if (detail != null) {
                    listener.onFailure(detail)
                } else if (response != null) {
                    listener.onFailure("http ${response.code}")
                } else {
                    listener.onFailure("connection failed")
                }
            }
        })
    }

    override fun sendBinary(data: ByteArray) {
        ws?.send(ByteString.of(*data))
    }

    override fun close(code: Int, reason: String) {
        ws?.close(code, reason)
    }

    // Stops the client's dispatcher and pool. Call on process teardown after
    // Transport.shutdown.
    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val PING_INTERVAL_MS = 30_000L

        private val NOOP = object : WireListener {
            override fun onOpen() {}
            override fun onBinary(data: ByteArray) {}
            override fun onClosed(code: Int, reason: String) {}
            override fun onFailure(message: String) {}
        }
    }
}
