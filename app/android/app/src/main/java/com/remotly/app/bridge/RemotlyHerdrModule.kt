package com.remotly.app.bridge

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.remotly.app.ssh.HerdrBridge
import com.remotly.app.specs.NativeRemotlyHerdrSpec

// The herdr bridge (remotly.herdr.*). One-shot command execution over SSH to a
// stored host. The host identity and credential come from the shared store;
// the host key is verified fail-closed against the accepted keys because a
// one-shot call cannot surface a first-use prompt.
class RemotlyHerdrModule(reactContext: ReactApplicationContext) :
    NativeRemotlyHerdrSpec(reactContext) {

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
