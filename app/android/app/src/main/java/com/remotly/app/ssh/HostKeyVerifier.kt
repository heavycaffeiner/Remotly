package com.remotly.app.ssh

// The result of checking a presented host key against the keys already accepted
// for a host and port. This is the fail-closed core of M4-02: a session must
// never be trusted on anything but Known, or an explicit first-use approval of
// New, and Changed is a hard stop until the user intentionally replaces it.
sealed class HostKeyVerdict {
    // A presented key matches an accepted key. Safe to trust.
    object Known : HostKeyVerdict()

    // No keys are recorded for this host and port. First use: the caller must
    // show the algorithm and fingerprint and require explicit approval before
    // persisting the key.
    object New : HostKeyVerdict()

    // Keys are recorded but none match. Reject the connection. expected is the
    // full accepted set and received is what the server presented, so the UI
    // can show both fingerprints.
    data class Changed(val expected: List<KnownHostKey>, val received: HostKeyInfo) : HostKeyVerdict()
}

// Compares a presented host key to the accepted keys for a host and port.
// Matching is on the hash part of the fingerprint, not the algorithm, so the
// same key reported under a different signature algorithm still verifies.
object HostKeyVerifier {

    fun verify(known: List<KnownHostKey>, received: HostKeyInfo): HostKeyVerdict {
        if (known.isEmpty()) return HostKeyVerdict.New
        val receivedHash = hashOf(received.fingerprint)
        return if (known.any { hashOf(it.fingerprint) == receivedHash }) {
            HostKeyVerdict.Known
        } else {
            HostKeyVerdict.Changed(known, received)
        }
    }

    fun isMatch(known: KnownHostKey, received: HostKeyInfo): Boolean =
        hashOf(known.fingerprint) == hashOf(received.fingerprint)

    // The canonical identity of a key. The bridge reports "SHA256:<base64>";
    // the hash part after the first colon is the stable identity. A bare
    // string with no colon is used as-is.
    fun hashOf(fingerprint: String): String {
        val i = fingerprint.indexOf(':')
        return if (i >= 0 && i < fingerprint.length - 1) {
            fingerprint.substring(i + 1)
        } else {
            fingerprint
        }
    }
}
