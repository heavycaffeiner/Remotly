package com.remotly.app.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostKeyVerifierTest {

    private fun known(fp: String, algo: String = "ssh-ed25519") = KnownHostKey(algo, fp)
    private fun received(fp: String, algo: String = "ssh-ed25519") = HostKeyInfo("h", 22, algo, fp)

    @Test
    fun newWhenNoKnownKeys() {
        val v = HostKeyVerifier.verify(emptyList(), received("SHA256:aaa"))
        assertTrue("expected New, got $v", v is HostKeyVerdict.New)
    }

    @Test
    fun knownWhenFingerprintMatches() {
        val v = HostKeyVerifier.verify(listOf(known("SHA256:aaa")), received("SHA256:aaa"))
        assertTrue("expected Known, got $v", v is HostKeyVerdict.Known)
    }

    @Test
    fun knownIgnoresReportedAlgorithm() {
        // The same key blob reported under a different signature algorithm still
        // verifies, because matching is on the hash, not the algorithm label.
        val v = HostKeyVerifier.verify(
            listOf(known("SHA256:aaa", "ssh-ed25519")),
            received("SHA256:aaa", "ssh-ed25519-cert-v01@openssh.com"),
        )
        assertTrue("expected Known, got $v", v is HostKeyVerdict.Known)
    }

    @Test
    fun changedWhenNothingMatches() {
        val v = HostKeyVerifier.verify(listOf(known("SHA256:aaa")), received("SHA256:bbb"))
        assertTrue("expected Changed, got $v", v is HostKeyVerdict.Changed)
        val changed = v as HostKeyVerdict.Changed
        assertEquals(1, changed.expected.size)
        assertEquals("SHA256:aaa", changed.expected[0].fingerprint)
        assertEquals("SHA256:bbb", changed.received.fingerprint)
    }

    @Test
    fun changedWhenAnyOfSeveralDiffer() {
        val known = listOf(known("SHA256:aaa"), known("SHA256:ccc"))
        val v = HostKeyVerifier.verify(known, received("SHA256:bbb"))
        assertTrue(v is HostKeyVerdict.Changed)
    }

    @Test
    fun knownWhenOneOfSeveralMatches() {
        val known = listOf(known("SHA256:aaa"), known("SHA256:ccc"))
        val v = HostKeyVerifier.verify(known, received("SHA256:ccc"))
        assertTrue("expected Known, got $v", v is HostKeyVerdict.Known)
    }

    @Test
    fun hashOfExtractsBase64Part() {
        assertEquals("aaa", HostKeyVerifier.hashOf("SHA256:aaa"))
        assertEquals("bare", HostKeyVerifier.hashOf("bare"))
    }
}
