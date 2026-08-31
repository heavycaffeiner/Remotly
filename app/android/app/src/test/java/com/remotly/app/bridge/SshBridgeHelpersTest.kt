package com.remotly.app.bridge

import com.remotly.app.ssh.KnownHostKey
import com.remotly.app.ssh.SftpEntry
import com.remotly.app.ssh.SshCredential
import com.remotly.app.ssh.SshHost
import com.remotly.app.ssh.SshHostStoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class SshBridgeHelpersTest {

    private fun host(credentialRef: String = "ref-1"): SshHost =
        SshHost(
            id = "h1:22",
            displayName = "build server",
            host = "10.0.0.5",
            port = 22,
            username = "dev",
            authKind = SshHost.AUTH_KEY,
            credentialRef = credentialRef,
            knownKeys = listOf(KnownHostKey("ssh-ed25519", "SHA256:abc")),
            createdAt = 100L,
            updatedAt = 200L,
        )

    // ---- credential building -------------------------------------------

    @Test
    fun passwordCredentialEncodesTheBytes() {
        val c = SshHostBridge.credential(false, "hunter2", null, null)
        assertTrue(c is SshCredential.Password)
        assertEquals("hunter2", (c as SshCredential.Password).value.decodeToString())
    }

    @Test
    fun keyCredentialDecodesBase64AndOmitsEmptyPassphrase() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END-----"
        val b64 = Base64.getEncoder().encodeToString(pem.toByteArray())
        val c = SshHostBridge.credential(true, null, b64, "")
        assertTrue(c is SshCredential.Key)
        val k = c as SshCredential.Key
        assertEquals(pem, k.privateKey.decodeToString())
        assertNull(k.passphrase)
    }

    @Test
    fun keyCredentialKeepsARealPassphrase() {
        val b64 = Base64.getEncoder().encodeToString("pem".toByteArray())
        val k = SshHostBridge.credential(true, null, b64, "pp") as SshCredential.Key
        assertEquals("pp", k.passphrase!!.decodeToString())
    }

    @Test
    fun keyAuthWithoutAKeyFails() {
        try {
            SshHostBridge.credential(true, null, "", null)
            fail("expected SshHostStoreException")
        } catch (e: SshHostStoreException) {
            assertTrue(e.message!!.contains("private key"))
        }
    }

    @Test
    fun passwordAuthWithoutAPasswordFails() {
        try {
            SshHostBridge.credential(false, "  ", null, null)
            fail("expected SshHostStoreException")
        } catch (e: SshHostStoreException) {
            assertTrue(e.message!!.contains("password"))
        }
    }

    // ---- view mapping ---------------------------------------------------

    @Test
    fun viewOmitsTheCredentialReference() {
        val v = SshHostBridge.toView(host(credentialRef = "secret-ref"))
        assertEquals("h1:22", v.id)
        assertEquals("build server", v.displayName)
        assertEquals(22, v.port)
        assertEquals(SshHost.AUTH_KEY, v.authKind)
        assertTrue(v.hasCredential)
        assertEquals(1, v.knownKeys.size)
        assertEquals("ssh-ed25519", v.knownKeys.first().algorithm)
        val json = SshHostBridge.hostJson(host())
        assertFalse("credential ref must not leak", "secret-ref" in json)
    }

    @Test
    fun aHostWithoutACredentialReportsNoCredential() {
        assertFalse(SshHostBridge.toView(host(credentialRef = "")).hasCredential)
    }

    @Test
    fun hostsJsonSerializesEveryRecord() {
        val json = SshHostBridge.hostsJson(listOf(host(), host().copy(id = "h2:22")))
        assertTrue(json.contains("h1:22"))
        assertTrue(json.contains("h2:22"))
    }

    // ---- sftp view serialization ---------------------------------------

    @Test
    fun entriesJsonRoundTripsNameBytes() {
        // An NFD name (e + combining acute) must survive serialization verbatim.
        val nfd = "cafe\u0301.txt"
        val json = SftpViewBridge.entriesJson(
            listOf(SftpEntry(nfd, false, false, 12L, 0L, 420)),
        )
        assertTrue(json.contains(nfd))
    }

    @Test
    fun entryJsonCarriesEveryField() {
        val json = SftpViewBridge.entryJson(SftpEntry("a/", true, false, 0L, 5L, 493))
        assertTrue(json.contains("\"name\":\"a/\""))
        assertTrue(json.contains("\"isDirectory\":true"))
        assertTrue(json.contains("\"permissions\":493")) // 0o755
    }

    @Test
    fun statusMapWithNoSessionReportsNone() {
        val map = SftpViewBridge.statusMap(null)
        assertEquals("NONE", map["state"])
        assertNull(map["hostKey"])
        assertNull(map["changed"])
    }
}
