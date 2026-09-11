package com.remotly.app.session

/**
 * A hand-driven stand-in for [SshHubTransport].
 *
 * Mirrors the jest.mock of `lib/ssh` in the TypeScript suite this package
 * replaces: connect, write, resize, and hostKey just record what they were
 * called with, and a test fires a "state" event through [emitState] exactly
 * as the real transport would once a connection outcome is known.
 */
class FakeSshTransport : SshTransport {
    data class Sized(val hostId: String, val sessionId: String, val cols: Int, val rows: Int)
    data class Written(val hostId: String, val sessionId: String, val bytes: ByteArray)

    val connectCalls = mutableListOf<Sized>()
    val writeCalls = mutableListOf<Written>()
    val resizeCalls = mutableListOf<Sized>()
    val closeCalls = mutableListOf<Pair<String, String>>()
    val closeHostCalls = mutableListOf<String>()
    val hostKeyCalls = mutableListOf<Triple<String, String, String>>()

    private val sinks = mutableMapOf<String, (String, Map<String, Any?>) -> Unit>()

    override fun connect(hostId: String, sessionId: String, cols: Int, rows: Int) {
        connectCalls += Sized(hostId, sessionId, cols, rows)
    }

    override fun write(hostId: String, sessionId: String, data: ByteArray) {
        writeCalls += Written(hostId, sessionId, data)
    }

    override fun resize(hostId: String, sessionId: String, cols: Int, rows: Int) {
        resizeCalls += Sized(hostId, sessionId, cols, rows)
    }

    override fun hostKey(hostId: String, sessionId: String, decision: String) {
        hostKeyCalls += Triple(hostId, sessionId, decision)
    }

    override fun close(hostId: String, sessionId: String) {
        closeCalls += hostId to sessionId
    }

    override fun closeHost(hostId: String) {
        closeHostCalls += hostId
    }

    override fun setEventSink(hostId: String, sink: ((String, Map<String, Any?>) -> Unit)?) {
        if (sink == null) sinks.remove(hostId) else sinks[hostId] = sink
    }

    /** Fires a "state" event for [sessionId] as SshHub would, e.g. `emitState(host, id, mapOf("state" to "active"))`. */
    fun emitState(hostId: String, sessionId: String, fields: Map<String, Any?>) {
        sinks[hostId]?.invoke("state", fields + ("sessionId" to sessionId))
    }
}
