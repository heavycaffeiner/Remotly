package com.remotly.app.ui.terminal

import com.remotly.app.terminal.KeyEncoding
import com.remotly.app.terminal.KeyMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalInputTest {
    @Test
    fun explicitShiftTabIsNativeShiftTab() {
        assertEquals(
            KeyEncoding(KeyMap.KEY_TAB, KeyMap.MOD_SHIFT, null),
            extraKeyEncoding("shift-tab", null),
        )
    }

    @Test
    fun latchedShiftTurnsTabIntoNativeShiftTab() {
        assertEquals(
            KeyEncoding(KeyMap.KEY_TAB, KeyMap.MOD_SHIFT, null),
            extraKeyEncoding("tab", ModifierKey.SHIFT),
        )
    }

    @Test
    fun toolKeysCarryNativeIdentityAndModifiers() {
        assertEquals(
            KeyEncoding(KeyMap.KEY_ARROW_UP, KeyMap.MOD_ALT, null),
            extraKeyEncoding("up", ModifierKey.ALT),
        )
        assertEquals(
            KeyEncoding(KeyMap.KEY_SLASH, KeyMap.MOD_CTRL, "/"),
            extraKeyEncoding("slash", ModifierKey.CTRL),
        )
        assertEquals(
            KeyEncoding(KeyMap.KEY_BACKSLASH, KeyMap.MOD_SHIFT, "|"),
            extraKeyEncoding("pipe", null),
        )
    }

    @Test
    fun latchedShiftTransformsPrintableTextAtKeyBoundary() {
        assertEquals(
            KeyEncoding(KeyMap.KEY_A, KeyMap.MOD_SHIFT, "A"),
            modifiedTextEncoding("a", ModifierKey.SHIFT),
        )
        assertEquals(
            KeyEncoding(KeyMap.KEY_DIGIT_0 + 1, KeyMap.MOD_SHIFT, "!"),
            modifiedTextEncoding("1", ModifierKey.SHIFT),
        )
        assertEquals(
            KeyEncoding(KeyMap.KEY_SLASH, KeyMap.MOD_SHIFT, "?"),
            modifiedTextEncoding("/", ModifierKey.SHIFT),
        )
    }

    @Test
    fun textEncodingPreservesInherentAndLatchedModifiers() {
        assertEquals(
            KeyEncoding(KeyMap.KEY_A, KeyMap.MOD_SHIFT or KeyMap.MOD_CTRL, "A"),
            modifiedTextEncoding("A", ModifierKey.CTRL),
        )
        assertEquals(
            KeyEncoding(KeyMap.KEY_BACKSLASH, KeyMap.MOD_SHIFT or KeyMap.MOD_ALT, "|"),
            modifiedTextEncoding("|", ModifierKey.ALT),
        )
    }

    @Test
    fun textWithoutOneAsciiKeyHasNoInventedEncoding() {
        assertNull(modifiedTextEncoding("ab", ModifierKey.CTRL))
        assertNull(modifiedTextEncoding("한", ModifierKey.CTRL))
        assertNull(modifiedTextEncoding("\n", ModifierKey.SHIFT))
    }

    @Test
    fun hardwareModifierCombinesWithExistingEncoding() {
        val encoding = KeyEncoding(KeyMap.KEY_A, KeyMap.MOD_CTRL, "a")

        assertEquals(
            KeyEncoding(KeyMap.KEY_A, KeyMap.MOD_CTRL or KeyMap.MOD_SHIFT, "A"),
            modifiedHardwareKey(encoding, ModifierKey.SHIFT),
        )
    }

    @Test
    fun hardwareModifierPreservesMultiCharacterText() {
        val encoding = KeyEncoding(KeyMap.KEY_UNIDENTIFIED, 0, "ab")

        assertEquals(
            KeyEncoding(KeyMap.KEY_UNIDENTIFIED, KeyMap.MOD_SHIFT, "ab"),
            modifiedHardwareKey(encoding, ModifierKey.SHIFT),
        )
    }
}
