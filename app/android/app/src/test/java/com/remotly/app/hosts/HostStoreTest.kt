package com.remotly.app.hosts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class HostStoreTest {

    @Rule
    @JvmField
    var tmp: TemporaryFolder = TemporaryFolder()

    private var now = 1_000_000L
    private val clock = { ++now }

    private fun store(file: File? = null): HostStore =
        HostStore(file ?: tmp.newFile("hosts.json"), clock)

    private fun pub(seed: Byte = 1): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32) { (it + seed).toByte() })

    private fun hint(kind: Int = 0, addr: String = "10.0.0.5", port: Int = 8443) =
        HostHint(kind, addr, port)

    @Test
    fun `missing file lists empty`() {
        val file = File(tmp.root, "nope/hosts.json")
        assertTrue(store(file).list().isEmpty())
    }

    @Test
    fun `add persists a record and list returns it`() {
        val s = store()
        val result = s.add("office-daemon", pub(1), listOf(hint()))
        val id = result.id
        assertFalse(result.duplicate)
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() }), id)

        val loaded = s.list()
        assertEquals(1, loaded.size)
        val r = loaded.single()
        assertEquals(id, r.id)
        assertEquals("office-daemon", r.daemonName)
        assertEquals(pub(1), r.daemonPub)
        assertEquals(listOf(hint()), r.hints)
        assertTrue(r.pairedAt > 0)
        assertTrue(r.lastConnectedAt >= r.pairedAt)
        assertTrue(File(tmp.root, "hosts.json").exists())
    }

    @Test
    fun `add with same pub refreshes name hints and last connected but keeps paired at`() {
        val s = store()
        s.add("old-name", pub(2), listOf(hint(addr = "10.0.0.9")))
        val first = s.list().single()
        val pairedAt = first.pairedAt

        val result = s.add("new-name", pub(2), listOf(hint(addr = "10.1.1.1")))
        assertTrue(result.duplicate)

        val r = s.list().single()
        assertEquals("new-name", r.daemonName)
        assertEquals(listOf(hint(addr = "10.1.1.1")), r.hints)
        assertEquals(pairedAt, r.pairedAt)
        assertTrue(r.lastConnectedAt > pairedAt)
    }

    @Test
    fun `add with different pub creates a new record and leaves the old one untouched`() {
        val s = store()
        s.add("a", pub(3), listOf(hint(addr = "10.0.0.1")))
        val result = s.add("b", pub(4), listOf(hint(addr = "10.0.0.2")))
        assertFalse(result.duplicate)
        assertEquals(2, s.list().size)
        val old = s.list().first { it.daemonName == "a" }
        assertEquals(listOf(hint(addr = "10.0.0.1")), old.hints)
    }

    @Test
    fun `remove deletes and reports unknown ids`() {
        val s = store()
        val id = s.add("a", pub(5), listOf(hint())).id
        assertTrue(s.remove(id))
        assertTrue(s.list().isEmpty())
        assertFalse(s.remove(id))
    }

    @Test
    fun `touch updates last connected and reports unknown ids`() {
        val s = store()
        val id = s.add("a", pub(6), listOf(hint())).id
        val before = s.list().single().lastConnectedAt
        assertTrue(s.touch(id))
        assertTrue(s.list().single().lastConnectedAt > before)
        assertFalse(s.touch("missing"))
    }

    @Test
    fun `stored timestamps are plain integers in json`() {
        val s = store()
        s.add("a", pub(7), listOf(hint()))
        val text = File(tmp.root, "hosts.json").readText()
        assertTrue(text.contains("\"version\": 1"))
        val paired = Regex("\"pairedAt\": (\\d+),?").find(text)
        assertNotNull(paired)
        assertEquals(now, paired!!.groupValues[1].toLong())
        val last = Regex("\"lastConnectedAt\": (\\d+),?").find(text)
        assertNotNull(last)
        assertEquals(now, last!!.groupValues[1].toLong())
    }

    @Test
    fun `corrupt json quarantines the file and starts fresh`() {
        val file = tmp.newFile("hosts.json")
        file.writeText("{ not json")
        val s = store(file)
        assertTrue(s.list().isEmpty())
        assertFalse(file.exists())
        val quarantined = file.parentFile.listFiles()
            ?.filter { it.name.startsWith("hosts.corrupt-") }
        assertNotNull(quarantined)
        assertEquals(1, quarantined!!.size)
        assertEquals("{ not json", quarantined.single().readText())

        val result = s.add("b", pub(8), listOf(hint()))
        assertFalse(result.duplicate)
        assertEquals(1, s.list().size)
    }

    @Test
    fun `record whose id does not match its pub quarantines the file`() {
        val file = tmp.newFile("hosts.json")
        val goodId = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 9).toByte() })
        file.writeText(
            """
            {"version":1,"hosts":[
              {"id":"$goodId","daemonName":"a","daemonPub":"${pub(99)}",
               "hints":[{"kind":0,"addr":"10.0.0.5","port":8443}],
               "pairedAt":1,"lastConnectedAt":1}
            ]}
            """.trimIndent()
        )
        val s = store(file)
        assertTrue(s.list().isEmpty())
        assertFalse(file.exists())
        assertNotNull(file.parentFile.listFiles { f, _ -> f.name.startsWith("hosts.corrupt-") })
    }

    @Test
    fun `unsupported version quarantines the file`() {
        val file = tmp.newFile("hosts.json")
        file.writeText("""{"version":2,"hosts":[]}""")
        val s = store(file)
        assertTrue(s.list().isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun `control character in name is rejected`() {
        val s = store()
        try {
            s.add("bad\u0000name", pub(10), listOf(hint()))
            fail("expected HostStoreException")
        } catch (e: HostStoreException) {
            assertTrue(e.message!!.contains("control character"))
        }
    }

    @Test
    fun `name longer than 100 chars is rejected`() {
        val s = store()
        try {
            s.add("x".repeat(101), pub(11), listOf(hint()))
            fail("expected HostStoreException")
        } catch (e: HostStoreException) {
            assertTrue(e.message!!.contains("out of range"))
        }
    }

    @Test
    fun `bad pub encodings are rejected`() {
        val s = store()
        for (pub in listOf(
            "short",
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(31) { 1 }),
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(33) { 1 }),
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 0 }),
            "not!base64url$".padEnd(43, 'a'),
        )) {
            try {
                s.add("a", pub, listOf(hint()))
                fail("expected HostStoreException for $pub")
            } catch (e: HostStoreException) {
                // expected
            }
        }
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun `bad hints are rejected`() {
        val s = store()
        for (hints in listOf(
            listOf(hint(kind = 4)),
            listOf(hint(port = 0)),
            listOf(hint(port = 65536)),
            listOf(hint(addr = "x".repeat(256))),
            List(9) { hint(addr = "10.0.0.$it") },
        )) {
            try {
                s.add("a", pub(12), hints)
                fail("expected HostStoreException")
            } catch (e: HostStoreException) {
                // expected
            }
        }
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun `survives reload with a fresh store instance`() {
        val file = tmp.newFile("hosts.json")
        val a = HostStore(file, clock)
        a.add("a", pub(13), listOf(hint(addr = "192.168.1.2")))
        a.add("b", pub(14), listOf(hint(kind = 1, addr = "fd00::1"), hint(kind = 2, addr = "host-a")))
        // A relay hint (kind 3) is stored alongside the direct hints.
        a.add("c", pub(15), listOf(hint(addr = "192.168.1.9"), hint(kind = 3, addr = "relay.example", port = 10000)))
        val b = HostStore(file, clock)
        assertEquals(3, b.list().size)
        assertEquals(listOf(hint(addr = "192.168.1.2")), b.list().first { it.daemonName == "a" }.hints)
        assertEquals(
            listOf(hint(kind = 1, addr = "fd00::1"), hint(kind = 2, addr = "host-a")),
            b.list().first { it.daemonName == "b" }.hints
        )
        assertEquals(
            listOf(hint(addr = "192.168.1.9"), hint(kind = 3, addr = "relay.example", port = 10000)),
            b.list().first { it.daemonName == "c" }.hints
        )
    }
}
