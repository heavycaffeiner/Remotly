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

    private fun assertFailure(action: () -> Unit) {
        try { action(); fail("expected settings error") } catch (_: SettingsStoreException) { }
    }

    @Test
    fun `strict field types preserve the original file`() {
        val file = File(tmp.root, "settings.json")
        for (text in listOf("""{"v":"5"}""", """{"v":5,"dynamicColor":"false"}""", """{"v":5,"terminalFontSize":14.5}""")) {
            file.writeText(text)
            assertFailure { store(file).load() }
            assertEquals(text, file.readText())
        }
    }

    @Test
    fun `legacy explicit key booleans migrate and gestures stay enabled`() {
        val file = File(tmp.root, "settings.json")
        for (enabled in listOf(true, false)) {
            file.writeText("""{"v":4,"showExtraKeyRow":$enabled,"filesSortKey":"size"}""")
            val settings = store(file).load()
            assertEquals(if (enabled) ExtraKeyVisibility.Always else ExtraKeyVisibility.Hidden, settings.extraKeyVisibility)
            assertTrue(settings.navigationGesturesEnabled)
            assertEquals(AppSettings.SORT_SIZE, settings.filesSortKey)
        }
        file.writeText("""{"v":4}""")
        assertEquals(ExtraKeyVisibility.Automatic, store(file).load().extraKeyVisibility)
    }

    @Test
    fun `unknown terminal id falls back and custom requires colors`() {
        val file = File(tmp.root, "settings.json")
        file.writeText("""{"v":5,"terminalTheme":{"id":"future"}}""")
        assertEquals("classic", store(file).load().terminalTheme.id)
        file.writeText("""{"v":5,"terminalTheme":{"id":"custom"}}""")
        assertFailure { store(file).load() }
    }

    @Test
    fun `invalid custom colors and palette sizes require recovery`() {
        val file = File(tmp.root, "settings.json")
        val s = store(file)
        s.save(AppSettings(terminalTheme = com.remotly.app.terminal.TerminalThemePreference("custom")))
        val valid = file.readText()
        val original = com.google.gson.JsonParser.parseString(valid).asJsonObject
        val custom = original.getAsJsonObject("terminalTheme").getAsJsonObject("custom")
        custom.addProperty("background", "#123")
        file.writeText(original.toString())
        assertFailure { s.load() }
        assertEquals(original.toString(), file.readText())
        file.writeText(valid)
        s.load()
        val palette = custom.getAsJsonArray("ansi")
        custom.addProperty("background", "#000000")
        palette.remove(15)
        file.writeText(original.toString())
        assertFailure { s.load() }
    }

    @Test
    fun `non JSON syntax and trailing data are rejected`() {
        val file = File(tmp.root, "settings.json")
        for (value in listOf("{v:5}", "{\"v\":5} {\"v\":5}", "/*comment*/{\"v\":5}")) {
            file.writeText(value)
            assertFailure { store(file).load() }
        }
    }

    @Test
    fun `saving without loading never overwrites malformed external data`() {
        val file = File(tmp.root, "settings.json")
        file.writeText("broken")
        assertFailure { store(file).save(AppSettings()) }
        assertEquals("broken", file.readText())
    }

    @Test
    fun `terminal fields round trip`() {
        val value = AppSettings(extraKeyVisibility = ExtraKeyVisibility.Hidden, navigationGesturesEnabled = true,
            terminalTheme = com.remotly.app.terminal.TerminalThemePreference("custom", com.remotly.app.terminal.TerminalThemes.Nord))
        store().save(value)
        assertEquals(value, store().load())
    }

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
    fun `corrupt file is preserved until explicit backed up recovery`() {
        val s = store()
        s.save(AppSettings(dynamicColor = false))
        File(tmp.root, "settings.json").writeText("{\"v\":1,\"not")
        val original = File(tmp.root, "settings.json").readText()
        assertFailure { s.load() }
        assertFailure { s.save(AppSettings()) }
        assertEquals(original, File(tmp.root, "settings.json").readText())
        assertEquals(AppSettings(), s.recoverWithDefaults())
        val backup = tmp.root.listFiles { f -> f.name.startsWith("settings.json.recovery-") }!!.single()
        assertEquals(original, backup.readText())
    }

    @Test
    fun `wrong version leaves original untouched`() {
        val file = File(tmp.root, "settings.json")
        file.writeText("""{"v":9,"themeMode":"dark"}""")
        assertFailure { store(file).load() }
        assertTrue(file.readText().contains("dark"))
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
        assertEquals(AppSettings(navigationGesturesEnabled = true), settings)
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
