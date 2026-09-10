package com.remotly.app.bridge

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.remotly.app.ssh.HerdrBridge
import com.remotly.app.specs.NativeRemotlyHerdrSpec

// The herdr bridge (remotly.herdr.*). Commands run over one held SSH
// connection per host, and a subscription is a command that keeps printing
// whose lines are forwarded through the onLine emitter. The host identity and
// credential come from the shared store; the host key is verified fail-closed
// against the accepted keys because a control call cannot surface a first-use
// prompt.
class RemotlyHerdrModule(reactContext: ReactApplicationContext) :
    NativeRemotlyHerdrSpec(reactContext) {

    override fun subscribe(hostId: String, command: String) {
        if (hostId.isBlank()) return
        HerdrBridge.execute<Unit>(
            onResult = { r ->
                // A dial that threw (no such host, no store) ends the
                // subscription the same way a broken one does, so the screen
                // has one path back to polling.
                r.onFailure { e -> emitEnd(hostId, "ssh_protocol", e.message ?: "subscribe failed") }
            },
            block = {
                HerdrBridge.subscribe(
                    hostId,
                    command,
                    onLine = { line ->
                        emitOnLine(
                            Arguments.makeNativeMap(mapOf("hostId" to hostId, "line" to line)),
                        )
                    },
                    onClosed = { code, message -> emitEnd(hostId, code, message) },
                )
            },
        )
    }

    override fun release(hostId: String) {
        if (hostId.isBlank()) return
        HerdrBridge.execute<Unit>(onResult = {}, block = { HerdrBridge.release(hostId) })
    }

    private fun emitEnd(hostId: String, code: String, message: String) {
        emitOnStreamEnd(
            Arguments.makeNativeMap(
                mapOf("hostId" to hostId, "code" to code, "message" to message),
            ),
        )
    }

    override fun exec(hostId: String, command: String, promise: Promise) {
        if (hostId.isBlank()) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "hostId is required")
            return
        }
        HerdrBridge.execute(
            onResult = { r ->
                r.fold(
                    { outcome ->
                        promise.resolve(
                            Arguments.makeNativeMap(
                                mapOf(
                                    "ok" to outcome.ok,
                                    "exitCode" to outcome.exitCode.toDouble(),
                                    "stdout" to HerdrBridge.encodeB64(outcome.stdout),
                                    "stderr" to HerdrBridge.encodeB64(outcome.stderr),
                                    "code" to outcome.code,
                                    "message" to outcome.message,
                                ),
                            ),
                        )
                    },
                    { e -> promise.reject(BridgeCodes.FAIL.toString(), e.message ?: "exec failed") },
                )
            },
            block = { HerdrBridge.exec(hostId, command) },
        )
    }
}
