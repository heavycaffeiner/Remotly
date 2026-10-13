package com.remotly.app.terminal

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.remotly.app.ui.terminal.ModifierKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HardwareKeyboardTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var view: TerminalView
    private val recorder = Recorder()

    private class Recorder : TerminalView.Host {
        val input = StringBuilder()
        var ready = false
        override fun onReady(cols: Int, rows: Int) { ready = true }
        override fun onError(code: String) { throw AssertionError(code) }
        override fun onInput(data: ByteArray) { input.append(data.toString(Charsets.UTF_8)) }
        override fun onResize(cols: Int, rows: Int) = Unit
        override fun onBell() = Unit
        override fun onTitle(title: String) = Unit
        override fun onFontSizeChange(fontSizeSp: Int) = Unit
        override fun onFocusChange(focused: Boolean) = Unit
        override fun onPtyWrite(data: ByteArray) = Unit
        override fun onSelectionChange(active: Boolean) = Unit
        override fun onPasteRequest() = Unit
        override fun onNotify(title: String, body: String) = Unit
        override fun onLinkCopied(link: String) = Unit
    }

    private fun mountTerminal() {
        compose.setContent {
            AndroidView(
                factory = { context ->
                    TerminalView(context).also {
                        view = it
                        it.host = recorder
                    }
                },
                onRelease = { it.release() },
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.runOnIdle {
            assertTrue(recorder.ready)
            assertTrue(view.requestFocus())
        }
    }

    private fun event(key: Int, action: Int = KeyEvent.ACTION_DOWN, mods: Int = 0, repeat: Int = 0): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(
            now, now, action, key, repeat, mods,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
    }

    private fun pressBeforeIme(key: Int, mods: Int = 0) {
        assertTrue(view.dispatchKeyEventPreIme(event(key, mods = mods)))
        assertTrue(view.dispatchKeyEventPreIme(event(key, KeyEvent.ACTION_UP, mods)))
    }

    @Test
    fun liveGboardTypingReachesTheNativeEncoder() {
        val expected = InstrumentationRegistry.getArguments().getString("gboardExpected")
        org.junit.Assume.assumeNotNull(expected)
        mountTerminal()
        compose.runOnIdle { view.openKeyboard() }
        android.util.Log.i("GboardLiveTest", "READY remotly")
        compose.waitUntil(timeoutMillis = 90000) { recorder.input.endsWith("\r") }
        compose.runOnIdle {
            val text = StringBuilder()
            for (code in recorder.input) {
                when (code) {
                    '\u007f', '\b' -> if (text.isNotEmpty()) text.delete(text.offsetByCodePoints(text.length, -1), text.length)
                    '\r' -> Unit
                    else -> text.append(code)
                }
            }
            assertEquals(expected, text.toString())
        }
    }

    @Test
    fun physicalShiftBetweenVowelAndDoubleFinalKeepsBetaRange() {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        mountTerminal()
        compose.runOnIdle {
            val connection = view.onCreateInputConnection(EditorInfo()) as TerminalInputConnection
            connection.commitText("ㅇ", 1)
            assertTrue(connection.replaceText(0, 1, "이", 1, null))
            assertTrue(view.dispatchKeyEvent(event(KeyEvent.KEYCODE_SHIFT_LEFT, mods = KeyEvent.META_SHIFT_ON)))
            assertTrue(connection.replaceText(0, 1, "있", 1, null))
            assertTrue(view.dispatchKeyEvent(event(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.ACTION_UP)))
            assertEquals("있", connection.getTextBeforeCursor(100, 0))
            assertFalse(connection.isClosed)
        }
    }

    @Test
    fun toolbarNavigationInvalidatesImeReplacementRanges() {
        if (android.os.Build.VERSION.SDK_INT < 34) return
        mountTerminal()
        compose.runOnIdle {
            val connection = view.onCreateInputConnection(EditorInfo()) as TerminalInputConnection
            connection.commitText("한", 1)
            view.sendExtraKey("left")
            assertFalse(connection.replaceText(0, 1, "글", 1, null))
            assertEquals("한\u001b[D", recorder.input.toString())
        }
    }

    @Test
    fun androidControlLookupDoesNotProvideThePrintableTextTheEncoderNeeds() {
        val key = event(KeyEvent.KEYCODE_C, mods = KeyEvent.META_CTRL_ON)
        assertTrue(key.getUnicodeChar(key.metaState) < ' '.code)
        assertEquals("c", KeyMap.encode(key).utf8)
    }

    @Test
    fun controlLettersKeepPrintableTextAndUseNativeEncodingForEitherControlKey() {
        mountTerminal()
        compose.runOnIdle {
            for (ctrl in listOf(KeyEvent.META_CTRL_LEFT_ON, KeyEvent.META_CTRL_RIGHT_ON)) {
                for (offset in 0 until 26) {
                    val key = KeyEvent.KEYCODE_A + offset
                    val encoding = KeyMap.encode(event(key, mods = ctrl))
                    assertEquals(('a' + offset).toString(), encoding.utf8)
                    assertEquals(KeyMap.MOD_CTRL, encoding.mods)
                    pressBeforeIme(key, ctrl)
                }
            }
            val alphabet = (1..26).joinToString("") { code ->
                // Native fixterms encoding distinguishes Ctrl+I from Tab
                // and Ctrl+M from Enter even without Kitty mode.
                when (code) {
                    9 -> "\u001b[105;5u"
                    13 -> "\u001b[109;5u"
                    else -> code.toChar().toString()
                }
            }
            assertEquals(alphabet + alphabet, recorder.input.toString())
        }
    }

    @Test
    fun controlPunctuationSpaceAndShiftAreEncodedAsTerminalChords() {
        mountTerminal()
        compose.runOnIdle {
            val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            pressBeforeIme(KeyEvent.KEYCODE_SPACE, ctrl)
            pressBeforeIme(KeyEvent.KEYCODE_LEFT_BRACKET, ctrl)
            pressBeforeIme(KeyEvent.KEYCODE_BACKSLASH, ctrl)
            pressBeforeIme(KeyEvent.KEYCODE_RIGHT_BRACKET, ctrl)
            pressBeforeIme(KeyEvent.KEYCODE_6, ctrl or KeyEvent.META_SHIFT_ON)
            pressBeforeIme(KeyEvent.KEYCODE_MINUS, ctrl or KeyEvent.META_SHIFT_ON)
            pressBeforeIme(KeyEvent.KEYCODE_C, ctrl or KeyEvent.META_SHIFT_ON)
            assertEquals("\u0000\u001b[91;5u\u001c\u001d\u001e\u001f\u001b[99;6u", recorder.input.toString())
        }
    }

    @Test
    fun escapeAndControlUseTheNegotiatedKittyProtocol() {
        mountTerminal()
        compose.runOnIdle {
            view.feed("\u001b[>9u".toByteArray())
            pressBeforeIme(KeyEvent.KEYCODE_ESCAPE)
            pressBeforeIme(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON)
            pressBeforeIme(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)
            assertEquals("\u001b[27u\u001b[99;5u\u001b[99;6u", recorder.input.toString())
        }
    }

    @Test
    fun escapeRepeatAndKeyUpDoNotLoseOrDuplicateInput() {
        mountTerminal()
        compose.runOnIdle {
            pressBeforeIme(KeyEvent.KEYCODE_ESCAPE)
            assertTrue(view.dispatchKeyEventPreIme(event(KeyEvent.KEYCODE_ESCAPE, repeat = 1)))
            assertTrue(view.dispatchKeyEventPreIme(event(KeyEvent.KEYCODE_ESCAPE, KeyEvent.ACTION_UP)))
            assertTrue(view.dispatchKeyEvent(event(KeyEvent.KEYCODE_ESCAPE)))
            assertTrue(view.dispatchKeyEvent(event(KeyEvent.KEYCODE_ESCAPE, KeyEvent.ACTION_UP)))
            val canceled = KeyEvent.changeFlags(event(KeyEvent.KEYCODE_ESCAPE), KeyEvent.FLAG_CANCELED)
            assertTrue(view.dispatchKeyEventPreIme(canceled))
            assertEquals("\u001b\u001b\u001b", recorder.input.toString())
        }
    }

    @Test
    fun shortcutsCancelPreeditAndRejectLateCommitsFromTheOldConnection() {
        mountTerminal()
        compose.runOnIdle {
            for (key in listOf(KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_C)) {
                val connection = view.onCreateInputConnection(EditorInfo()) as TerminalInputConnection
                connection.setComposingText("pending", 1)
                assertTrue(connection.isComposing)
                pressBeforeIme(key, if (key == KeyEvent.KEYCODE_C) KeyEvent.META_CTRL_ON else 0)
                assertFalse(connection.isComposing)
                connection.commitText("pending", 1)
            }
            assertEquals("\u001b\u0003", recorder.input.toString())
        }
    }

    @Test
    fun imeForwardedControlChordAlsoCancelsPreeditAndSendsOnce() {
        mountTerminal()
        compose.runOnIdle {
            for (key in listOf(KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER)) {
                val connection = view.onCreateInputConnection(EditorInfo()) as TerminalInputConnection
                connection.setComposingText("pending", 1)
                assertTrue(connection.sendKeyEvent(event(key, mods = KeyEvent.META_CTRL_ON)))
                assertTrue(connection.sendKeyEvent(event(key, KeyEvent.ACTION_UP, KeyEvent.META_CTRL_ON)))
                assertFalse(connection.isComposing)
                connection.commitText("pending", 1)
            }
            assertEquals("\u0003\u0000\u001b[27;5;13~", recorder.input.toString())
        }
    }

    @Test
    fun controlPressAndReleaseDoNotConsumeTheExtraRowModifierOrLatchControl() {
        mountTerminal()
        compose.runOnIdle {
            var consumed = 0
            view.inputModifier = ModifierKey.ALT
            view.onModifierConsumed = { consumed++ }
            pressBeforeIme(KeyEvent.KEYCODE_CTRL_LEFT)
            assertEquals(ModifierKey.ALT, view.inputModifier)
            assertEquals(0, consumed)
            pressBeforeIme(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_LEFT_ON)
            assertEquals(1, consumed)
            assertFalse(view.dispatchKeyEventPreIme(event(KeyEvent.KEYCODE_A)))
            assertTrue(view.dispatchKeyEvent(event(KeyEvent.KEYCODE_A)))
            assertTrue(view.dispatchKeyEvent(event(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP)))
            assertEquals("\u001b\u0003a", recorder.input.toString())
        }
    }

    @Test
    fun systemKeysForwardedByTheImeNeverReachTheRemoteTerminal() {
        mountTerminal()
        compose.runOnIdle {
            val connection = view.onCreateInputConnection(EditorInfo()) as TerminalInputConnection
            for (key in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_POWER)) {
                connection.sendKeyEvent(event(key))
                connection.sendKeyEvent(event(key, KeyEvent.ACTION_UP))
            }
            assertEquals("", recorder.input.toString())
        }
    }

    @Test
    fun escapeAndControlReachTheFocusedTerminalThroughWindowDispatch() {
        mountTerminal()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for ((key, mods) in listOf(
            KeyEvent.KEYCODE_ESCAPE to 0,
            KeyEvent.KEYCODE_C to (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
        )) {
            instrumentation.sendKeySync(event(key, mods = mods))
            instrumentation.sendKeySync(event(key, KeyEvent.ACTION_UP, mods))
        }
        compose.runOnIdle {
            assertEquals("\u001b\u0003", recorder.input.toString())
            assertTrue(view.isFocused)
        }
    }

    @Test
    fun ordinaryTypingAndAndroidBackAreNotClaimedBeforeTheIme() {
        mountTerminal()
        compose.runOnIdle {
            for (key in listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL)) {
                assertFalse(view.dispatchKeyEventPreIme(event(key)))
            }
            assertFalse(view.dispatchKeyEventPreIme(event(KeyEvent.KEYCODE_BACK)))
            assertFalse(view.dispatchKeyEventPreIme(event(KeyEvent.KEYCODE_BACK, mods = KeyEvent.META_CTRL_ON)))
            assertEquals("", recorder.input.toString())
        }
    }
}
