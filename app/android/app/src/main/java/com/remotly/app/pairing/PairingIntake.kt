package com.remotly.app.pairing

import java.util.concurrent.atomic.AtomicReference

// Single pending pairing URI from the deep-link handler.
//
// React Navigation handles the warm and cold deep link through its linking
// config. This is the cold-start fallback for an intent that arrives before JS
// is ready: the pairing screen drains the URI with takePending, which is
// atomic and one-shot, so a payload is delivered exactly once per deep link.
// The URI itself is not logged.
object PairingIntake {
    private val pending = AtomicReference<String?>(null)

    fun submit(uri: String) {
        pending.set(uri)
    }

    fun takePending(): String = pending.getAndSet(null).orEmpty()
}
