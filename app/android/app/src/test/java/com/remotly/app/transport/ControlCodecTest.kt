package com.remotly.app.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ControlCodecTest {
    private val codec = ControlCodec()
    private val sid = "0123456789abcdef".repeat(4)

    @Test
    fun `encode hello omits null fields`() {
        val pub = ByteArray(32) { it.toByte() }
        val json = codec.encodeRequest(codec.hello(1, "Pixel", pub))
        assertEquals(
            """{"id":1,"type":"hello","device_name":"Pixel","device_pub":"${Base64Url.encode(pub)}"}""",
            json,
        )
    }

    @Test
    fun `encode session create carries the set fields`() {
        val json = codec.encodeRequest(
            codec.sessionCreate(7, SessionKind.AGENT, title = "job", command = "ls", cwd = "/tmp", cols = 80, rows = 24),
        )
        assertEquals(
            """{"id":7,"type":"session.create","kind":"agent","title":"job","command":"ls","cwd":"/tmp","cols":80,"rows":24}""",
            json,
        )
    }

    @Test
    fun `encode list is envelope only`() {
        assertEquals("""{"id":3,"type":"session.list"}""", codec.encodeRequest(codec.sessionList(3)))
        assertEquals(
            """{"id":4,"type":"session.detach","channel_id":9}""",
            codec.encodeRequest(codec.sessionDetach(4, 9)),
        )
    }

    @Test
    fun `decode hello response`() {
        val pub = Base64Url.encode(ByteArray(32) { 0xff.toByte() })
        val msg = codec.decode("""{"id":1,"type":"hello","daemon_name":"host","daemon_pub":"$pub"}""")
        assertTrue(msg is ControlResponse)
        val r = msg as ControlResponse
        assertEquals(1L, r.id)
        assertEquals("host", r.daemonName)
        assertEquals(pub, r.daemonPub)
    }

    @Test
    fun `decode create and list responses`() {
        val meta = """{"id":"$sid","title":"shell","kind":"shell","command":"","cwd":"/home","cols":80,"rows":24,"created_at":"2026-01-01T00:00:00Z","last_activity":"2026-01-01T00:00:01Z","running":true}"""
        val created = codec.decode("""{"id":2,"type":"session.create","session":$meta}""") as ControlResponse
        assertEquals(sid, created.session!!.id)
        assertTrue(created.session!!.running)
        assertNull(created.session!!.exit)

        val listed = codec.decode("""{"id":3,"type":"session.list","sessions":[$meta]}""") as ControlResponse
        assertEquals(1, listed.sessions!!.size)
        val empty = codec.decode("""{"id":3,"type":"session.list","sessions":[]}""") as ControlResponse
        assertEquals(0, empty.sessions!!.size)

        val attached = codec.decode("""{"id":4,"type":"session.attach","channel_id":5}""") as ControlResponse
        assertEquals(5L, attached.channelId)
    }

    @Test
    fun `decode error response`() {
        val r = codec.decode(
            """{"id":5,"type":"session.kill","error":{"code":"unknown_session","message":"no such session"}}""",
        ) as ControlResponse
        assertEquals("unknown_session", r.error!!.code)
        assertEquals("no such session", r.error!!.message)
    }

    @Test
    fun `decode notifications`() {
        val close = codec.decode("""{"type":"channel.close","channel_id":1,"reason":"session_exited"}""")
        assertTrue(close is ControlNotification)
        val cn = close as ControlNotification
        assertEquals(1L, cn.channelId)
        assertEquals("session_exited", cn.reason)

        val meta = """{"id":"$sid","title":"t","kind":"shell","command":"","cwd":"/","cols":80,"rows":24,"created_at":"x","last_activity":"y","running":false,"exit":{"code":0,"signal":""}}"""
        val upd = codec.decode("""{"type":"session.update","session":$meta}""") as ControlNotification
        assertEquals(0, upd.session!!.exit!!.code)
    }

    @Test
    fun `decode rejects malformed frames`() {
        for (bad in listOf(
            """{"id":1}""",                                  // missing type
            """not json""",                                   // not an object
            """{"type":"channel.close","channel_id":1}""",   // missing reason
            """{"type":"channel.close","channel_id":1,"reason":"bogus"}""", // bad reason
            """{"type":"session.update"}""",                 // missing session
            """{"type":"channel.replay_complete","channel_id":1}""", // missing offset
            """{"type":"channel.replay_complete","offset":4}""", // missing channel_id
            """{"type":"channel.replay_complete","channel_id":1,"offset":-1}""", // negative
            """{"type":"channel.replay_complete","channel_id":1,"offset":1e18}""", // beyond bound
        )) {
            try {
                codec.decode(bad)
                fail("expected rejection of $bad")
            } catch (e: ControlException) {
                // expected
            }
        }
    }

    @Test
    fun `check session create mirrors daemon rules`() {
        ControlCodec.checkSessionCreate(SessionKind.SHELL, null, null, null, null, null)
        ControlCodec.checkSessionCreate(SessionKind.AGENT, null, "ls", null, null, null)
        ControlCodec.checkSessionCreate(SessionKind.AGENT, "t", "ls", "/tmp", 80, 24)
        val rejects = listOf(
            arrayOf("shell", null as String?, "ls"),      // shell with command
            arrayOf("agent", null as String?, null),      // agent without command
            arrayOf("bogus", null as String?, null),      // bad kind
            arrayOf("agent", null as String?, "x".repeat(5000)), // command too long
        )
        for (c in rejects) {
            try {
                ControlCodec.checkSessionCreate(c[0] as String?, c[1], c[2], null, null, null)
                fail("expected rejection for kind=${c[0]}")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `session id validation`() {
        assertTrue(ControlCodec.isValidSessionId(sid))
        assertFalse(ControlCodec.isValidSessionId("0123456789abcdef"))
        assertFalse(ControlCodec.isValidSessionId("0123456789ABCDEF".repeat(4)))
    }

    @Test
    fun `encode attach with and without cursor`() {
        assertEquals(
            """{"id":6,"type":"session.attach","session_id":"$sid"}""",
            codec.encodeRequest(codec.sessionAttach(6, sid)),
        )
        assertEquals(
            """{"id":7,"type":"session.attach","session_id":"$sid","resume_from":1234}""",
            codec.encodeRequest(codec.sessionAttach(7, sid, 1234)),
        )
        assertEquals(
            """{"id":8,"type":"preset.list"}""",
            codec.encodeRequest(codec.presetList(8)),
        )
    }

    @Test
    fun `decode attach response carries continuity and replay offset`() {
        val r = codec.decode(
            """{"id":9,"type":"session.attach","channel_id":5,"continuity":"gapless","replayed_from":42}""",
        ) as ControlResponse
        assertEquals(5L, r.channelId)
        assertEquals(Continuity.GAPLESS, r.continuity)
        assertEquals(42L, r.replayedFrom)

        val full = codec.decode(
            """{"id":10,"type":"session.attach","channel_id":1,"continuity":"full","replayed_from":0}""",
        ) as ControlResponse
        assertEquals(Continuity.FULL, full.continuity)
        assertEquals(0L, full.replayedFrom)

        // A response without the M2 fields still decodes (older daemon).
        val plain = codec.decode("""{"id":11,"type":"session.attach","channel_id":2}""") as ControlResponse
        assertNull(plain.continuity)
        assertNull(plain.replayedFrom)
    }

    @Test
    fun `decode preset list response`() {
        val r = codec.decode(
            """{"id":12,"type":"preset.list","presets":[{"name":"agent","command":"codex","icon_hint":"bot"}]}""",
        ) as ControlResponse
        val presets = r.presets!!
        assertEquals(1, presets.size)
        assertEquals("agent", presets[0].name)
        assertEquals("codex", presets[0].command)
        assertEquals("bot", presets[0].iconHint)

        val empty = codec.decode("""{"id":13,"type":"preset.list","presets":[]}""") as ControlResponse
        assertEquals(0, empty.presets!!.size)
    }

    @Test
    fun `decode session update carries preview`() {
        val meta = """{"id":"$sid","title":"t","kind":"shell","command":"","cwd":"/","cols":80,"rows":24,"created_at":"x","last_activity":"y","running":true,"preview":"last line"}"""
        val upd = codec.decode("""{"type":"session.update","session":$meta}""") as ControlNotification
        assertEquals("last line", upd.session!!.preview)
    }

    @Test
    fun `decode session event bell and pattern`() {
        val bell = codec.decode(
            """{"type":"session.event","session_id":"$sid","seq":1,"kind":"bell","text":"done","ts":1750000000}""",
        ) as ControlNotification
        val b = codec.toSessionEvent(bell)
        assertEquals(sid, b.sessionId)
        assertEquals(1L, b.seq)
        assertEquals(EventKind.BELL, b.kind)
        assertNull(b.pattern)
        assertEquals("done", b.text)
        assertEquals(1750000000L, b.ts)

        val pat = codec.decode(
            """{"type":"session.event","session_id":"$sid","seq":2,"kind":"pattern","pattern":"error","text":"error: boom","ts":1750000001}""",
        ) as ControlNotification
        val p = codec.toSessionEvent(pat)
        assertEquals(EventKind.PATTERN, p.kind)
        assertEquals("error", p.pattern)
        assertEquals("error: boom", p.text)
    }

    @Test
    fun `decode rejects bad attach and preset responses`() {
        val bad = listOf(
            """{"id":14,"type":"session.attach","channel_id":1,"continuity":"bogus"}""",
            """{"id":15,"type":"session.attach","channel_id":1,"continuity":"full","replayed_from":-1}""",
            """{"id":16,"type":"session.attach","channel_id":1,"replayed_from":${ControlLimits.MAX_RESUME_FROM + 1}}""",
            """{"id":17,"type":"preset.list","presets":[${presets(17)}]}""",
            """{"id":18,"type":"preset.list","presets":[{"name":"","command":"ls"}]}""",
            """{"id":19,"type":"preset.list","presets":[{"name":"x","command":""}]}""",
            """{"id":20,"type":"preset.list","presets":[{"name":"${"n".repeat(51)}","command":"ls"}]}""",
            """{"id":21,"type":"preset.list","presets":[{"name":"x","command":"ls","icon_hint":"${"i".repeat(33)}"}]}""",
        )
        for (json in bad) {
            try {
                codec.decode(json)
                fail("expected rejection of $json")
            } catch (e: ControlException) {
                // expected
            }
        }
    }

    @Test
    fun `decode rejects malformed session events`() {
        val cases = listOf(
            """{"type":"session.event","session_id":"$sid","seq":0,"kind":"bell","ts":1}""",        // seq zero
            """{"type":"session.event","session_id":"$sid","seq":${ControlLimits.MAX_EVENT_SEQ + 1},"kind":"bell","ts":1}""", // seq too big
            """{"type":"session.event","session_id":"$sid","seq":1,"kind":"bell","ts":-1}""",         // negative ts
            """{"type":"session.event","session_id":"$sid","seq":1,"kind":"bogus","ts":1}""",          // bad kind
            """{"type":"session.event","session_id":"$sid","seq":1,"kind":"bell","pattern":"x","ts":1}""", // bell with pattern
            """{"type":"session.event","session_id":"$sid","seq":1,"kind":"pattern","ts":1}""",          // pattern missing name
            """{"type":"session.event","session_id":"$sid","seq":1,"kind":"pattern","pattern":"${"p".repeat(51)}","ts":1}""", // pattern too long
            """{"type":"session.event","session_id":"$sid","seq":1,"kind":"bell","text":"${"t".repeat(121)}","ts":1}""", // text too long
            """{"type":"session.event","session_id":"nothex","seq":1,"kind":"bell","ts":1}""",           // bad session id
            """{"type":"session.event","session_id":"$sid","kind":"bell","ts":1}""",                      // missing seq
            """{"type":"session.event","session_id":"$sid","seq":1,"kind":"bell"}""",                     // missing ts
        )
        for (json in cases) {
            try {
                codec.decode(json)
                fail("expected rejection of event")
            } catch (e: ControlException) {
                // expected
            }
        }
    }

    @Test
    fun `check session attach mirrors daemon cursor rules`() {
        ControlCodec.checkSessionAttach(sid, null)
        ControlCodec.checkSessionAttach(sid, 0)
        ControlCodec.checkSessionAttach(sid, ControlLimits.MAX_RESUME_FROM)
        val rejects = listOf(
            "not-a-session-id" to null,
            sid to -1L,
            sid to ControlLimits.MAX_RESUME_FROM + 1,
        )
        for ((id, cursor) in rejects) {
            try {
                ControlCodec.checkSessionAttach(id, cursor)
                fail("expected rejection for $id / $cursor")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    // ControlResponse declares only the fields the native side acts on, so
    // re-encoding it drops the fs.* and transfer.* results. The bridge
    // forwards `raw` instead; a truncated fs.list read as an empty directory
    // and every transfer.create failed for a missing transfer_id.
    @Test
    fun `decode keeps the daemon json for fields it does not model`() {
        val listJson =
            """{"id":20,"type":"fs.list","entries":[{"name":"a.txt","size":11}],"more":false,"total":1}"""
        val listed = codec.decode(listJson) as ControlResponse
        assertEquals(listJson, listed.raw)

        val createJson =
            """{"id":21,"type":"transfer.create","transfer_id":"t-1","channel_id":3,"expected_size":99}"""
        val created = codec.decode(createJson) as ControlResponse
        assertEquals(createJson, created.raw)
        // The modelled fields still decode alongside the passthrough.
        assertEquals(3L, created.channelId)
    }

    private fun presets(n: Int): String =
        (1..n).joinToString(",") { """{"name":"p$it","command":"ls"}""" }
}
