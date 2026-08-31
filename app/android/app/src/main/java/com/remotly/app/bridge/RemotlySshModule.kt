package com.remotly.app.bridge

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.remotly.app.ssh.SshHub
import com.remotly.app.terminal.TerminalStore
import com.remotly.app.transport.Base64Std
import com.remotly.app.specs.NativeRemotlySshSpec

// One-shot hand-off of the host the SSH terminal page should open. The opening
// page stores it just before navigating; the page drains it on mount. The value
// is consumed once so a stale signal from a previous open cannot leak through.
object SshOpenParams {
    @Volatile
    private var open: String? = null

    fun store(hostId: String) {
        open = hostId
    }

    // Returns and clears the stored value.
    fun take(): String? {
        val value = open
        open = null
        return value
    }
}

// The SSH terminal bridge (remotly.ssh.*). Sessions are keyed by
// (hostId, sessionId), so one host can back several terminal tabs. Driven by
// SshHub (which runs the Go sshcore engine). connect starts the session and
// returns immediately; state and terminal bytes arrive through the onState and
// onData emitters, bound to the hub's per-host event sink.
class RemotlySshModule(reactContext: ReactApplicationContext) :
    NativeRemotlySshSpec(reactContext) {

    override fun connect(
        hostId: String,
        sessionId: String,
        cols: Double,
        rows: Double,
        promise: Promise,
    ) {
        if (hostId.isBlank()) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "hostId is required")
            return
        }
        if (!isValidSessionId(sessionId)) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "sessionId is invalid")
            return
        }
        bindEvents(hostId)
        SshHub.connect(hostId, sessionId, cols.toInt(), rows.toInt())
        promise.resolve(null)
    }

    override fun write(hostId: String, sessionId: String, data: String, promise: Promise) {
        if (data.isEmpty()) {
            promise.resolve(null)
            return
        }
        if (!isValidSessionId(sessionId)) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "sessionId is invalid")
            return
        }
        val bytes = try {
            Base64Std.decode(data)
        } catch (e: Exception) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "data is not valid base64")
            return
        }
        SshHub.write(hostId, sessionId, bytes)
        promise.resolve(null)
    }

    override fun resize(
        hostId: String,
        sessionId: String,
        cols: Double,
        rows: Double,
        promise: Promise,
    ) {
        SshHub.resize(hostId, sessionId, cols.toInt(), rows.toInt())
        promise.resolve(null)
    }

    override fun hostKey(
        hostId: String,
        sessionId: String,
        decision: String,
        promise: Promise,
    ) {
        SshHub.hostKey(hostId, sessionId, decision)
        promise.resolve(null)
    }

    override fun close(hostId: String, sessionId: String, promise: Promise) {
        SshHub.close(hostId, sessionId)
        // The terminal for this tab is retained across screens, so closing the
        // session is what frees it. Leaving it would hold its scrollback for a
        // session that can never come back.
        //
        // Keyed by the bare session id, which is what the screen binds to the
        // native view and what lib/sshSessions writes background output under.
        // A host-prefixed key freed nothing, so every closed tab leaked its
        // terminal until the store's cap evicted it.
        TerminalStore.release(sessionId)
        promise.resolve(null)
    }

    override fun closeHost(hostId: String, promise: Promise) {
        SshHub.closeHost(hostId)
        promise.resolve(null)
    }

    override fun storeOpen(hostId: String, promise: Promise) {
        if (hostId.length < 1 || hostId.length > 512) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "hostId out of range")
            return
        }
        SshOpenParams.store(hostId)
        promise.resolve(null)
    }

    override fun takeOpen(promise: Promise) {
        promise.resolve(Arguments.makeNativeMap(mapOf("hostId" to (SshOpenParams.take() ?: ""))))
    }

    // Binds the hub's per-host event sink to this module's emitters.
    private fun bindEvents(hostId: String) {
        SshHub.setEventSink(hostId) { name, data -> emitEvent(name, data) }
    }

    // Session ids are minted by the app, but they arrive here across the bridge
    // and become a map key, so they are validated rather than trusted. The
    // separator the hub builds its key from must not appear inside one.
    private fun isValidSessionId(sessionId: String): Boolean =
        sessionId.isNotEmpty() &&
            sessionId.length <= 64 &&
            sessionId.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    // The hub posts on the main thread and the codegen emitters are thread-safe,
    // so no extra hop is added.
    private fun emitEvent(name: String, data: Map<String, Any?>) {
        // The event's code is typed as a string; a CloseCode number (closed) is
        // stringified so both closed and failed carry a string code.
        val normalized = data.toMutableMap()
        (normalized["code"] as? Number)?.let { normalized["code"] = it.toString() }
        val map = Arguments.makeNativeMap(normalized)
        when (name) {
            "state" -> emitOnState(map)
            "data" -> emitOnData(map)
        }
    }
}
