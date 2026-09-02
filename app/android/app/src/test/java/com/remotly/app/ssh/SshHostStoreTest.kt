package com.remotly.app.ssh

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SshHostStoreTest {

    private lateinit var dir: File
    private lateinit var store: SshHostStore
    private lateinit var writer: FailableWriter
    // One key for the whole test, standing in for the keystore key, which is
    // stable across store instances on a device.
    private lateinit var cipher: CredentialCipher
    private var now = 1_000L

    // Wraps the real writer so a test can fail the durable write and assert the
    // previous document survived intact.
    private class FailableWriter(private val delegate: AtomicWriter = FileAtomicWriter()) :
        AtomicWriter {
        @Volatile var failNext = false
        @Volatile var writes = 0

        override fun write(target: File, bytes: ByteArray) {
            if (failNext) throw SshHostStoreException("injected write failure")
            writes++
            delegate.write(target, bytes)
        }
    }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("rq-ssh-store").toFile()
        writer = FailableWriter()
        cipher = inMemoryCipher()
        store = newStore()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun newStore(): SshHostStore =
        SshHostStore(File(dir, SshHostStore.FILE_NAME), cipher, { now }, writer)

    private fun pw(s: String) = SshCredential.Password(s.toByteArray())

    private fun storeFile() = File(dir, SshHostStore.FILE_NAME)

    // --- basic behavior ------------------------------------------------------

    @Test
    fun addPersistsAndSealsCredential() {
        // A distinctive secret: the sealed blob is base64, so a short common
        // string like "pw" turns up in the ciphertext by chance and the leak
        // check fails at random.
        val secret = "correct-horse-battery-staple"
        val h = store.add("My Server", "example.com", 22, "alice", pw(secret))
        assertEquals("example.com", h.host)
        assertEquals(22, h.port)
        assertEquals(SshHost.AUTH_PASSWORD, h.authKind)
        assertTrue(h.credentialRef.isNotEmpty())
        val cred = store.credential(h.id) as SshCredential.Password
        assertArrayEquals(secret.toByteArray(), cred.value)
        // The plaintext never lands in the document.
        assertFalse(
            "password leaked to store file",
            storeFile().readText().contains(secret),
        )
    }

    @Test
    fun addSameEndpointUpdatesNotDuplicates() {
        val a = store.add("A", "example.com", 22, "alice", pw("pw1"))
        val b = store.add("B", "example.com", 22, "bob", pw("pw2"))
        assertEquals(a.id, b.id)
        assertEquals(1, store.list().size)
        assertEquals("B", store.get(a.id)!!.displayName)
        val cred = store.credential(a.id) as SshCredential.Password
        assertArrayEquals("pw2".toByteArray(), cred.value)
    }

    @Test
    fun differentPortsAreDifferentHosts() {
        val a = store.add("A", "example.com", 22, "alice", pw("pw"))
        val b = store.add("B", "example.com", 2222, "alice", pw("pw"))
        assertNotEquals(a.id, b.id)
        assertEquals(2, store.list().size)
    }

    @Test
    fun removeCascadesCredential() {
        val h = store.add("A", "example.com", 22, "alice", pw("pw"))
        assertTrue(store.remove(h.id))
        assertNull(store.get(h.id))
        assertFalse(storeFile().readText().contains(h.credentialRef))
    }

    @Test
    fun removeLeavesUnrelatedHostsIntact() {
        val a = store.add("A", "a.example", 22, "u", pw("p"))
        val b = store.add("B", "b.example", 22, "u", pw("p"))
        store.remove(a.id)
        assertEquals(1, store.list().size)
        assertEquals("b.example", store.list().single().host)
        assertArrayEquals("p".toByteArray(), (store.credential(b.id) as SshCredential.Password).value)
    }

    @Test
    fun acceptHostKeyDedupsAndReplaceDropsPrior() {
        val h = store.add("A", "example.com", 22, "alice", pw("pw"))
        store.acceptHostKey(h.id, KnownHostKey("ssh-ed25519", "SHA256:aaa"))
        assertEquals(1, store.get(h.id)!!.knownKeys.size)
        store.acceptHostKey(h.id, KnownHostKey("ssh-ed25519", "SHA256:aaa"))
        assertEquals("duplicate key should not be re-added", 1, store.get(h.id)!!.knownKeys.size)
        store.acceptHostKey(h.id, KnownHostKey("ssh-ed25519", "SHA256:ccc"))
        assertEquals(2, store.get(h.id)!!.knownKeys.size)
        store.replaceHostKeys(h.id, KnownHostKey("ssh-ed25519", "SHA256:bbb"))
        val keys = store.get(h.id)!!.knownKeys
        assertEquals(1, keys.size)
        assertEquals("SHA256:bbb", keys[0].fingerprint)
    }

    @Test
    fun setCredentialRotatesAndDropsTheOldBlob() {
        val h = store.add("A", "example.com", 22, "alice", pw("pw1"))
        val oldRef = h.credentialRef
        store.setCredential(h.id, pw("pw2"))
        val updated = store.get(h.id)!!
        assertNotEquals(oldRef, updated.credentialRef)
        assertFalse("old blob should be gone", storeFile().readText().contains(oldRef))
        assertArrayEquals(
            "pw2".toByteArray(),
            (store.credential(h.id) as SshCredential.Password).value,
        )
    }

    @Test
    fun keyCredentialRoundTripsWithPassphrase() {
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END-----\n".toByteArray()
        val h = store.add("A", "example.com", 22, "alice", SshCredential.Key(pem, "pass".toByteArray()))
        assertEquals(SshHost.AUTH_KEY, h.authKind)
        val cred = store.credential(h.id) as SshCredential.Key
        assertArrayEquals(pem, cred.privateKey)
        assertArrayEquals("pass".toByteArray(), cred.passphrase)
    }

    // --- D1, D2, D3: a failed write must not destroy the old credential ------

    @Test
    fun failedCredentialRotationLeavesTheOldCredentialUsable() {
        val h = store.add("A", "example.com", 22, "alice", pw("original"))
        writer.failNext = true
        try {
            store.setCredential(h.id, pw("replacement"))
            fail("expected the injected write failure")
        } catch (e: SshHostStoreException) {
            // expected
        }
        writer.failNext = false
        // The old credential still decrypts, from a freshly loaded store.
        val reopened = newStore()
        val cred = reopened.credential(h.id) as SshCredential.Password
        assertArrayEquals("original".toByteArray(), cred.value)
        assertEquals(h.credentialRef, reopened.get(h.id)!!.credentialRef)
    }

    @Test
    fun failedEndpointUpdateLeavesTheOldRecordIntact() {
        val h = store.add("A", "example.com", 22, "alice", pw("pw"))
        writer.failNext = true
        try {
            store.update(h.id, SshHostStore.HostPatch(host = "other.example"))
            fail("expected the injected write failure")
        } catch (e: SshHostStoreException) {
            // expected
        }
        writer.failNext = false
        val reopened = newStore()
        assertEquals(1, reopened.list().size)
        assertEquals("example.com", reopened.get(h.id)!!.host)
        assertArrayEquals("pw".toByteArray(), (reopened.credential(h.id) as SshCredential.Password).value)
    }

    @Test
    fun failedAddDoesNotOrphanTheNewCredential() {
        store.add("A", "a.example", 22, "u", pw("first"))
        val before = storeFile().readText()
        writer.failNext = true
        try {
            store.add("B", "b.example", 22, "u", pw("second"))
            fail("expected the injected write failure")
        } catch (e: SshHostStoreException) {
            // expected
        }
        writer.failNext = false
        // The document is byte-identical: no orphaned blob was left behind.
        assertEquals(before, storeFile().readText())
        assertEquals(1, newStore().list().size)
    }

    @Test
    fun failedRemoveKeepsTheHostConnectable() {
        val h = store.add("A", "example.com", 22, "alice", pw("pw"))
        writer.failNext = true
        try {
            store.remove(h.id)
            fail("expected the injected write failure")
        } catch (e: SshHostStoreException) {
            // expected
        }
        writer.failNext = false
        val reopened = newStore()
        assertEquals(1, reopened.list().size)
        assertArrayEquals("pw".toByteArray(), (reopened.credential(h.id) as SshCredential.Password).value)
    }

    // --- D4: update -----------------------------------------------------------

    @Test
    fun metadataUpdateKeepsCredentialAndHostKeys() {
        val h = store.add("A", "example.com", 22, "alice", pw("pw"))
        store.acceptHostKey(h.id, KnownHostKey("ssh-ed25519", "SHA256:aaa"))
        val updated = store.update(h.id, SshHostStore.HostPatch(displayName = "Renamed"))
        assertEquals("Renamed", updated.displayName)
        assertEquals(h.id, updated.id)
        assertEquals(h.credentialRef, updated.credentialRef)
        assertEquals(1, updated.knownKeys.size)
        assertArrayEquals("pw".toByteArray(), (store.credential(h.id) as SshCredential.Password).value)
    }

    @Test
    fun endpointChangeUpdatesHostAndPortAndYieldsANewId() {
        val h = store.add("A", "example.com", 22, "alice", pw("pw"))
        val updated = store.update(
            h.id,
            SshHostStore.HostPatch(host = "other.example", port = 2222),
        )
        assertEquals("other.example", updated.host)
        assertEquals(2222, updated.port)
        assertNotEquals(h.id, updated.id)
        assertNull("the old id must not resolve", store.get(h.id))
        // The credential follows the record to the new endpoint.
        assertArrayEquals(
            "pw".toByteArray(),
            (store.credential(updated.id) as SshCredential.Password).value,
        )
    }

    @Test
    fun endpointChangeClearsInheritedHostKeyTrust() {
        val h = store.add("A", "example.com", 22, "alice", pw("pw"))
        store.acceptHostKey(h.id, KnownHostKey("ssh-ed25519", "SHA256:aaa"))
        val updated = store.update(h.id, SshHostStore.HostPatch(host = "other.example"))
        assertTrue("trust must not transfer to a new endpoint", updated.knownKeys.isEmpty())
    }

    @Test
    fun updateRejectsCollidingWithAnExistingEndpoint() {
        val a = store.add("A", "a.example", 22, "u", pw("p"))
        store.add("B", "b.example", 22, "u", pw("p"))
        try {
            store.update(a.id, SshHostStore.HostPatch(host = "b.example"))
            fail("expected a collision to be rejected")
        } catch (e: SshHostStoreException) {
            assertTrue(e.message!!.contains("endpoint"))
        }
    }

    @Test
    fun clearKnownKeysDropsTrustWithoutChangingTheEndpoint() {
        val h = store.add("A", "example.com", 22, "alice", pw("pw"))
        store.acceptHostKey(h.id, KnownHostKey("ssh-ed25519", "SHA256:aaa"))
        val updated = store.update(h.id, SshHostStore.HostPatch(clearKnownKeys = true))
        assertEquals(h.id, updated.id)
        assertTrue(updated.knownKeys.isEmpty())
    }

    // --- D5: empty display name ----------------------------------------------

    @Test
    fun emptyDisplayNameIsAccepted() {
        val h = store.add("", "example.com", 22, "alice", pw("pw"))
        assertEquals("", h.displayName)
        assertEquals("", newStore().get(h.id)!!.displayName)
    }

    @Test
    fun rejectsControlCharacterDisplayName() {
        try {
            store.add("bad\u0007name", "example.com", 22, "alice", pw("pw"))
            fail("control character should be rejected")
        } catch (e: SshHostStoreException) {
            // expected
        }
    }

    // --- validation ----------------------------------------------------------

    @Test
    fun rejectsWhitespaceHost() {
        try {
            store.add("A", "exa mple.com", 22, "alice", pw("pw"))
            fail("whitespace host should be rejected")
        } catch (e: SshHostStoreException) {
            // expected
        }
    }

    @Test
    fun rejectsBadPort() {
        try {
            store.add("A", "example.com", 0, "alice", pw("pw"))
            fail("port 0 should be rejected")
        } catch (e: SshHostStoreException) {
            // expected
        }
    }

    @Test
    fun rejectsControlCharUsername() {
        try {
            store.add("A", "example.com", 22, "al\nice", pw("pw"))
            fail("control-char username should be rejected")
        } catch (e: SshHostStoreException) {
            // expected
        }
    }

    @Test
    fun acceptsWindowsUsernameForms() {
        for (name in listOf("alice", "MACHINE\\alice", "DOMAIN\\alice", "alice@domain.example")) {
            val h = store.add("A", "example.com", 22, name, pw("pw"))
            assertEquals(name, store.get(h.id)!!.username)
            store.remove(h.id)
        }
    }

    @Test
    fun corruptFileQuarantinesAndStartsFresh() {
        store.add("A", "example.com", 22, "alice", pw("pw"))
        storeFile().writeText("{ not json")
        assertTrue("load should start fresh after quarantine", store.list().isEmpty())
        val quarantined = dir.listFiles()?.any { it.name.startsWith("ssh-store.corrupt-") } ?: false
        assertTrue("a quarantined side-file should exist", quarantined)
    }

    @Test
    fun endpointIdIsStable() {
        assertEquals(store.endpointId("example.com", 22), store.endpointId("example.com", 22))
        assertNotEquals(store.endpointId("example.com", 22), store.endpointId("example.com", 23))
    }

    @Test
    fun hostIsCanonicalizedBeforeStorage() {
        val h = store.add("A", "EXAMPLE.com", 22, "alice", pw("pw"))
        assertEquals("example.com", h.host)
        // The same endpoint in another spelling resolves to the same record.
        val again = store.add("B", "example.COM", 22, "alice", pw("pw"))
        assertEquals(h.id, again.id)
        assertEquals(1, store.list().size)
    }

    // --- migration from schema 1 ---------------------------------------------

    private fun writeLegacy(hostsJson: String, secretsJson: String) {
        File(dir, SshHostStore.LEGACY_HOSTS_NAME).writeText(hostsJson)
        File(dir, SshHostStore.LEGACY_SECRETS_NAME).writeText(secretsJson)
    }

    // Builds a schema 1 pair whose blob is sealed by the supplied cipher, the
    // way the previous release wrote it.
    private fun legacyFixture(cipher: CredentialCipher): String {
        val sealed = cipher.seal(CredentialCodec.encode(pw("legacy-pw")))
        val blob = java.util.Base64.getEncoder().encodeToString(sealed)
        val id = SshEndpoint.legacyEndpointId("example.com", 22)
        writeLegacy(
            """
            {"version":1,"hosts":[{"id":"$id","displayName":"Old","host":"example.com",
            "port":22,"username":"alice","authKind":0,"credentialRef":"ref-1",
            "knownKeys":[{"algorithm":"ssh-ed25519","fingerprint":"SHA256:aaa"}],
            "createdAt":500,"updatedAt":600}]}
            """.trimIndent(),
            """{"blobs":{"ref-1":"$blob"}}""",
        )
        return id
    }

    @Test
    fun migratesSchema1PreservingHostsKeysAndCredentials() {
        val id = legacyFixture(cipher)
        val migrated = SshHostStore(storeFile(), cipher, { now }, writer)

        val hosts = migrated.list()
        assertEquals(1, hosts.size)
        val h = hosts.single()
        assertEquals(id, h.id)
        assertEquals("Old", h.displayName)
        assertEquals("example.com", h.host)
        assertEquals(500L, h.createdAt)
        assertEquals(1, h.knownKeys.size)
        assertEquals("SHA256:aaa", h.knownKeys[0].fingerprint)
        assertArrayEquals(
            "legacy-pw".toByteArray(),
            (migrated.credential(id) as SshCredential.Password).value,
        )
    }

    @Test
    fun migrationRenamesTheOldFilesRatherThanDeletingThem() {
        legacyFixture(cipher)
        SshHostStore(storeFile(), cipher, { now }, writer).list()

        assertFalse(File(dir, SshHostStore.LEGACY_HOSTS_NAME).exists())
        val kept = dir.listFiles()?.filter { it.name.contains(".migrated-") } ?: emptyList()
        assertEquals("both legacy files should be kept aside", 2, kept.size)
    }

    @Test
    fun migrationIsIdempotent() {
        val id = legacyFixture(cipher)
        SshHostStore(storeFile(), cipher, { now }, writer).list()
        // A second store over the same directory reads schema 2 and does not
        // re-run the migration.
        val second = SshHostStore(storeFile(), cipher, { now }, writer)
        assertEquals(1, second.list().size)
        assertArrayEquals(
            "legacy-pw".toByteArray(),
            (second.credential(id) as SshCredential.Password).value,
        )
    }

    @Test
    fun migrationWithAMissingCredentialIsReportedAndPreservesTheOldFiles() {
        val id = SshEndpoint.legacyEndpointId("example.com", 22)
        writeLegacy(
            """
            {"version":1,"hosts":[{"id":"$id","displayName":"Old","host":"example.com",
            "port":22,"username":"alice","authKind":0,"credentialRef":"missing",
            "knownKeys":[],"createdAt":500,"updatedAt":600}]}
            """.trimIndent(),
            """{"blobs":{}}""",
        )
        val migrated = SshHostStore(storeFile(), cipher, { now }, writer)
        try {
            migrated.list()
            fail("expected an inconsistency report")
        } catch (e: SshStoreInconsistentException) {
            // The old data is left exactly where it was, to try again.
            assertTrue(File(dir, SshHostStore.LEGACY_HOSTS_NAME).exists())
        }
    }

    @Test
    fun migrationDoesNotRunWhenThereIsNoLegacyFile() {
        assertTrue(store.list().isEmpty())
        assertFalse(File(dir, SshHostStore.LEGACY_HOSTS_NAME).exists())
    }

    @Test
    fun aDocumentReferencingAMissingCredentialIsReportedNotTrimmed() {
        val h = store.add("A", "example.com", 22, "alice", pw("pw"))
        // Drop the credential map but keep the host, as a partial write would.
        val text = storeFile().readText().replace(Regex("\"credentials\"\\s*:\\s*\\{[^}]*}"), "\"credentials\":{}")
        storeFile().writeText(text)
        try {
            newStore().list()
            fail("expected an inconsistency report")
        } catch (e: SshStoreInconsistentException) {
            assertTrue(e.message!!.contains("credential"))
        }
        // The file was preserved rather than quarantined or rewritten.
        assertTrue(storeFile().readText().contains(h.id))
    }
}
