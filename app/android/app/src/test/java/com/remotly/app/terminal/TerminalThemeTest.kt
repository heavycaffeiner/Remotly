package com.remotly.app.terminal

import com.remotly.app.settings.TerminalThemeDraft
import com.remotly.app.settings.TerminalThemeDraftState
import org.junit.Assert.*
import org.junit.Test

class TerminalThemeTest {
    @Test fun `all presets meet default contrast and palette limits`() {
        assertEquals(6, TerminalThemes.presets.size)
        TerminalThemes.presets.forEach { (id, theme) ->
            assertTrue("$id ${theme.contrast}", theme.contrast >= 4.5)
            assertEquals(16, theme.ansi.size)
            assertEquals(19, theme.nativeColors().size)
        }
        assertEquals(0xff586e75.toInt(), TerminalThemes.SolarizedLight.foreground)
    }

    @Test fun `only opaque six digit hex values are accepted`() {
        assertEquals(0xffabcdef.toInt(), parseTerminalColor("#abcdef"))
        for (value in listOf("#abc", "#12345678", "red", "123456", " #123456", "#GG1234")) {
            try { parseTerminalColor(value); fail(value) } catch (_: IllegalArgumentException) { }
        }
        try { TerminalThemes.Classic.copy(ansi = listOf(0xff000000.toInt())); fail() }
        catch (_: IllegalArgumentException) { }
        try { TerminalThemes.Classic.copy(background = 0x000000); fail() }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun `unknown preference resolves classic`() {
        assertEquals(TerminalThemes.Classic, TerminalThemePreference("unknown").resolve())
    }

    @Test fun `draft preserves invalid text until cancel without changing preference`() {
        val preference = TerminalThemePreference("nord")
        TerminalThemeDraftState.cancel()
        TerminalThemeDraftState.begin(preference.resolve())
        TerminalThemeDraftState.edit(0, "#12")
        TerminalThemeDraftState.begin(TerminalThemes.Dracula)
        assertEquals("#12", TerminalThemeDraftState.draft.value!!.values[0])
        assertTrue(runCatching { TerminalThemeDraftState.draft.value!!.resolve() }.isFailure)
        assertEquals(TerminalThemes.Nord, preference.resolve())
        TerminalThemeDraftState.cancel()
        assertNull(TerminalThemeDraftState.draft.value)
        assertEquals(TerminalThemes.Nord, TerminalThemeDraft.from(TerminalThemes.Nord).resolve())
    }
}
