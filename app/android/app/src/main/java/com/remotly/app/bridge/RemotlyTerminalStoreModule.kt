package com.remotly.app.bridge

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.remotly.app.specs.NativeRemotlyTerminalStoreSpec
import com.remotly.app.terminal.TerminalStore
import com.remotly.app.transport.Base64Std

// Writes into a terminal that has no view attached (remotly.terminalStore.*).
//
// Output for a tab that is not on screen goes straight into its own terminal,
// which owns the scrollback and the parser. The alternative, a queue in JS,
// has to discard something once it fills, and discarding from the front cuts
// an escape sequence in half.
class RemotlyTerminalStoreModule(reactContext: ReactApplicationContext) :
    NativeRemotlyTerminalStoreSpec(reactContext) {

    override fun feed(
        sessionId: String,
        data: String,
        cols: Double,
        rows: Double,
        promise: Promise,
    ) {
        if (sessionId.isBlank()) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "sessionId is required")
            return
        }
        val bytes = try {
            Base64Std.decode(data)
        } catch (e: IllegalArgumentException) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "data is not valid base64")
            return
        }
        // Resolved once the write has actually run, so the caller's next chunk
        // waits for this one. That is the backpressure that stops a busy
        // session from burying the main thread in queued writes.
        TerminalStore.feed(sessionId, bytes, cols.toInt(), rows.toInt()) { written ->
            promise.resolve(written)
        }
    }

    override fun has(sessionId: String, promise: Promise) {
        promise.resolve(TerminalStore.has(sessionId))
    }
}
