package com.remotly.app.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceStoreTest {

    @Rule
    @JvmField
    var tmp: TemporaryFolder = TemporaryFolder()

    // A valid 64-hex-character daemon session id.
    private val SID = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2"

    private fun store(): WorkspaceStore = WorkspaceStore(tmp.root)

    private fun doc(hostId: String = "host-abc", tabs: String = "[]", active: String = "null") =
        """{"v":1,"hostId":"$hostId","activeSessionId":$active,"tabs":$tabs}"""

    private fun tab(
        sid: String = SID,
        cursor: Long = 0,
        state: String = "attaching",
        title: String = "sh",
        kind: String = "shell",
    ): String {
        val escapedTitle = title.replace("\n", "\\n")
        return "{\"sessionId\":\"$sid\",\"title\":\"$escapedTitle\",\"kind\":\"$kind\",\"cursor\":$cursor,\"state\":\"$state\"}"
    }

    @Test
    fun `missing workspace loads null`() {
        assertNull(store().load("host-abc"))
    }

    @Test
    fun `save and load round-trip the document`() {
        val s = store()
        val tabs = "[" + tab(SID, 42, "attached", "tty1", "agent") + "]"
        val json = doc(tabs = tabs)
        s.save("host-abc", json)
        assertEquals(json, s.load("host-abc"))
    }

    @Test
    fun `hosts are isolated`() {
        val s = store()
        s.save("host-a", doc("host-a"))
        assertNull(s.load("host-b"))
        assertEquals(doc("host-a"), s.load("host-a"))
    }

    @Test
    fun `clear deletes the document`() {
        val s = store()
        s.save("host-abc", doc())
        s.clear("host-abc")
        assertNull(s.load("host-abc"))
        // Clearing an unknown host is a no-op.
        s.clear("host-abc")
    }

    @Test
    fun `save rejects a document for another host`() {
        val s = store()
        try {
            s.save("host-abc", doc("host-evil"))
            fail("expected validation failure")
        } catch (e: WorkspaceValidationException) {
            assertTrue(e.message!!.contains("host id"))
        }
    }

    @Test
    fun `save rejects invalid structure`() {
        val s = store()
        val badSid1 = tab(sid = "not-hex")
        val badSid2 = tab(sid = "a1b2")
        val badCursor = tab(cursor = -1)
        val badState = tab(state = "bogus")
        // A raw newline inside the JSON string literal makes the document invalid.
        val badTitle =
            "{\"sessionId\":\"$SID\",\"title\":\"bad\ncontrol\",\"kind\":\"shell\",\"cursor\":0,\"state\":\"attaching\"}"
        val tooMany = List(17) { tab() }.joinToString(",")
        val bad = listOf(
            "not json",
            """{"v":2,"hostId":"host-abc","tabs":[]}""",
            """{"hostId":"host-abc","tabs":[]}""",
            """{"v":1,"hostId":"host-abc"}""",
            """{"v":1,"hostId":"host-abc","tabs":"nope"}""",
            """{"v":1,"hostId":"host-abc","tabs":[$badSid1]}""",
            """{"v":1,"hostId":"host-abc","tabs":[$badSid2]}""",
            """{"v":1,"hostId":"host-abc","tabs":[$badCursor]}""",
            """{"v":1,"hostId":"host-abc","tabs":[$badState]}""",
            """{"v":1,"hostId":"host-abc","tabs":[$badTitle]}""",
            """{"v":1,"hostId":"host-abc","tabs":[$tooMany]}""",
            """{"v":1,"hostId":"host-abc","activeSessionId":"nope","tabs":[]}""",
        )
        for (json in bad) {
            try {
                s.save("host-abc", json)
                fail("expected validation failure for: $json")
            } catch (e: WorkspaceValidationException) {
                // expected
            }
        }
    }

    @Test
    fun `save rejects duplicate session ids`() {
        val s = store()
        val json = """{"v":1,"hostId":"host-abc","tabs":[${tab()},${tab()}]}"""
        try {
            s.save("host-abc", json)
            fail("expected validation failure")
        } catch (e: WorkspaceValidationException) {
            assertTrue(e.message!!.contains("duplicate"))
        }
    }

    @Test
    fun `cursor at the javascript bound is accepted beyond it is not`() {
        val s = store()
        val max = (1L shl 53) - 1
        s.save("host-abc", doc(tabs = "[" + tab(cursor = max) + "]"))
        assertTrue((s.load("host-abc") ?: "").contains("\"cursor\":$max"))
        try {
            s.save("host-abc", doc(tabs = "[" + tab(cursor = max + 1) + "]"))
            fail("expected validation failure")
        } catch (e: WorkspaceValidationException) {
            assertTrue(e.message!!.contains("cursor"))
        }
    }

    @Test
    fun `corrupt file is quarantined and loads null`() {
        val s = store()
        s.save("host-abc", doc())
        // Simulate torn write corruption.
        File(tmp.root, "workspace-host-abc.json").writeText("{\"v\":1,\"ho")
        assertNull(s.load("host-abc"))
        val quarantined =
            tmp.root.listFiles { f -> f.name.startsWith("workspace-host-abc.json.corrupt-") }
        assertEquals(1, quarantined?.size ?: 0)
        // A fresh save works after quarantine.
        s.save("host-abc", doc())
        assertEquals(doc(), s.load("host-abc"))
    }

    @Test
    fun `save does not clobber an existing document on validation failure`() {
        val s = store()
        val good = doc(tabs = "[" + tab() + "]")
        s.save("host-abc", good)
        try {
            s.save("host-abc", "not json")
            fail("expected validation failure")
        } catch (e: WorkspaceValidationException) {
            // expected
        }
        assertEquals(good, s.load("host-abc"))
    }

    @Test
    fun `host id with path characters is rejected`() {
        val s = store()
        for (id in listOf("", "a/b", "a\\b", "a b", "a".repeat(65), "..", "a\nb")) {
            try {
                s.load(id)
                fail("expected validation failure for id: ${id.replace("\n", "\\n")}")
            } catch (e: WorkspaceValidationException) {
                // expected
            }
        }
    }
}
