package com.remotly.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsStoreTest {

    @Rule
    @JvmField
    var tmp: TemporaryFolder = TemporaryFolder()

    private fun store(file: File? = null): SettingsStore =
        SettingsStore(file ?: File(tmp.root, "settings.json"))

    @Test
    fun `missing file loads defaults`() {
        val settings = store(File(tmp.root, "nope/settings.json")).load()
        assertEquals(AppSettings(), settings)
    }

    @Test
    fun `save and load round-trip`() {
        val s = store()
        s.save(AppSettings(dynamicColor = false))
        assertFalse(s.load().dynamicColor)
        s.save(AppSettings(dynamicColor = true))
        assertTrue(s.load().dynamicColor)
    }

    @Test
    fun `corrupt file is quarantined and defaults returned`() {
        val s = store()
        s.save(AppSettings(dynamicColor = false))
        File(tmp.root, "settings.json").writeText("{\"v\":1,\"not")
        val settings = s.load()
        assertEquals(AppSettings(), settings)
        val quarantined = tmp.root.listFiles { f -> f.name.startsWith("settings.corrupt-") }
        assertEquals(1, quarantined?.size ?: 0)
        // A fresh save works after quarantine.
        s.save(AppSettings(dynamicColor = false))
        assertFalse(s.load().dynamicColor)
    }

    @Test
    fun `wrong version is quarantined`() {
        val file = File(tmp.root, "settings.json")
        file.writeText("""{"v":9,"themeMode":"dark"}""")
        val settings = store(file).load()
        assertEquals(AppSettings(), settings)
    }

    @Test
    fun `a version 1 file migrates to current defaults for every field it lacks`() {
        // Version 1 predates every field this schema has; an upgrade must not
        // fail to load just because the file is old.
        val file = File(tmp.root, "settings.json")
        file.writeText("""{"v":1,"notifyEnabled":true}""")
        val settings = store(file).load()
        assertEquals(AppSettings.THEME_SYSTEM, settings.themeMode)
        assertEquals(AppSettings.DEFAULT_FONT_SIZE, settings.terminalFontSize)
        assertEquals(AppSettings.CURSOR_BLOCK, settings.cursorStyle)
    }

    @Test
    fun `all fields round-trip`() {
        val s = store()
        val saved = AppSettings(
            themeMode = AppSettings.THEME_DARK,
            dynamicColor = false,
            terminalFontSize = 20,
            openKeyboardOnTerminal = false,
            showExtraKeyRow = false,
            cursorStyle = AppSettings.CURSOR_BAR,
        )
        s.save(saved)
        assertEquals(saved, s.load())
    }

    @Test
    fun `an out of range font size is clamped on save`() {
        val s = store()
        s.save(AppSettings(terminalFontSize = 999))
        assertEquals(AppSettings.MAX_FONT_SIZE, s.load().terminalFontSize)
        s.save(AppSettings(terminalFontSize = 1))
        assertEquals(AppSettings.MIN_FONT_SIZE, s.load().terminalFontSize)
    }

    @Test
    fun `an unknown theme or cursor falls back rather than failing the load`() {
        val file = File(tmp.root, "settings.json")
        file.writeText(
            """{"v":3,"themeMode":"neon","cursorStyle":"beam"}""",
        )
        val settings = store(file).load()
        assertEquals(AppSettings.THEME_SYSTEM, settings.themeMode)
        assertEquals(AppSettings.CURSOR_BLOCK, settings.cursorStyle)
    }

    @Test
    fun `saving defaults restores every field`() {
        val s = store()
        s.save(
            AppSettings(
                themeMode = AppSettings.THEME_DARK,
                dynamicColor = false,
                terminalFontSize = 30,
                openKeyboardOnTerminal = false,
                showExtraKeyRow = false,
                cursorStyle = AppSettings.CURSOR_UNDERLINE,
            ),
        )
        s.save(AppSettings())
        assertEquals(AppSettings(), s.load())
    }

    @Test
    fun `a failed write leaves the previous settings readable`() {
        // A directory in the file's place makes the atomic replace fail.
        val s = store()
        s.save(AppSettings(dynamicColor = false))
        val before = s.load()

        val blocked = File(tmp.root, "blocked.json")
        assertTrue(blocked.mkdirs())
        try {
            SettingsStore(blocked).save(AppSettings(dynamicColor = true))
            fail("expected a store exception")
        } catch (e: SettingsStoreException) {
            // expected
        }
        assertEquals(before, s.load())
    }

    @Test
    fun `missing fields fall back to defaults`() {
        val file = File(tmp.root, "settings.json")
        file.writeText("""{"v":3}""")
        val settings = store(file).load()
        assertEquals(AppSettings(), settings)
    }

    @Test
    fun `write failure surfaces as a store exception`() {
        // A directory where the file should go makes the atomic rename fail.
        val target = File(tmp.root, "settings.json")
        assertTrue(target.mkdirs())
        val s = SettingsStore(target)
        try {
            s.save(AppSettings(dynamicColor = false))
            fail("expected a store exception")
        } catch (e: SettingsStoreException) {
            assertTrue(e.message!!.contains("settings"))
        }
    }
}
