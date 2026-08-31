package com.remotly.app.bridge

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.remotly.app.pairing.PairingIntake
import com.remotly.app.specs.NativeRemotlyPairingSpec

// The pairing deep-link handoff (remotly.pairing.*). The URI is pairing
// material: it is handed to the parser, never logged.
class RemotlyPairingModule(reactContext: ReactApplicationContext) :
    NativeRemotlyPairingSpec(reactContext) {

    // One-shot: takePending returns the current value and clears it; an empty
    // string means no pending link.
    override fun takePending(promise: Promise) {
        promise.resolve(Arguments.makeNativeMap(mapOf("uri" to PairingIntake.takePending())))
    }
}
