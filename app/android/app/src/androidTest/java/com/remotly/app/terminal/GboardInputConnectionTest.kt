package com.remotly.app.terminal

import android.os.Build
import android.view.KeyEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GboardInputConnectionTest {
    private class Recorder : TerminalSink {
        val text = StringBuilder()
        override fun sendText(text: String) { this.text.append(text) }
        override fun sendKey(event: KeyEvent, composing: Boolean) {
            if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL && text.isNotEmpty()) {
                text.delete(text.offsetByCodePoints(text.length, -1), text.length)
            }
        }
        override fun onCompositionChanged(state: CompositionState) = Unit
    }

    @Test fun betaReplacementsAndLegacyCompositionShareAnEditorDocument() {
        if (Build.VERSION.SDK_INT < 34) return
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val sink = Recorder()
            val view = View(InstrumentationRegistry.getInstrumentation().targetContext)
            val connection = TerminalInputConnection(view, sink)
            connection.commitText("ㅇ", 1)
            connection.beginBatchEdit()
            assertTrue(connection.replaceText(0, 1, "아", 1, null))
            assertTrue(connection.replaceText(0, 1, "안", 1, null))
            connection.endBatchEdit()
            connection.commitText("ㄴ", 1)
            connection.replaceText(1, 2, "녀", 1, null)
            connection.replaceText(1, 2, "녕", 1, null)
            connection.commitText(" ", 1)
            connection.setComposingText("한ㄱ", 1)
            assertEquals("안녕 한ㄱ", connection.getTextBeforeCursor(100, 0))
            connection.setComposingText("한글", 1)
            connection.commitText("한글", 1)
            assertEquals("안녕 한글", sink.text.toString())
            val surrounding = connection.getSurroundingText(100, 100, 0)!!
            assertEquals("안녕 한글", surrounding.text.toString())
            assertEquals(5, surrounding.selectionEnd)
            assertEquals(0, surrounding.offset)
            connection.closeConnection()
        }
    }

    @Test fun aDiscardedReplacementConnectionDoesNotCloseTheServedConnection() {
        if (Build.VERSION.SDK_INT < 34) return
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val sink = Recorder()
            val view = View(InstrumentationRegistry.getInstrumentation().targetContext)
            val first = TerminalInputConnection(view, sink)
            val next = TerminalInputConnection(view, first)
            assertTrue(first.commitText("ㅇ", 1))
            assertTrue(first.replaceText(0, 1, "아", 1, null))
            first.closeConnection()
            assertFalse(first.replaceText(0, 1, "글", 1, null))
            assertTrue(next.replaceText(0, 1, "안", 1, null))
            assertEquals("안", sink.text.toString())
            next.closeSession()
        }
    }

    @Test fun closingTheSessionInvalidatesEveryOutstandingWrapper() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val sink = Recorder()
            val view = View(InstrumentationRegistry.getInstrumentation().targetContext)
            val first = TerminalInputConnection(view, sink)
            val next = TerminalInputConnection(view, first)
            next.closeSession()
            assertFalse(first.commitText("stale", 1))
            assertFalse(first.setComposingText("stale", 1))
            assertFalse(first.beginBatchEdit())
            assertFalse(first.hasLiveSession)
            assertEquals("", sink.text.toString())
        }
    }

    @Test fun codepointDeletesAndStaleCallbacksAreValidated() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val sink = Recorder()
            val view = View(InstrumentationRegistry.getInstrumentation().targetContext)
            val connection = TerminalInputConnection(view, sink)
            connection.setComposingText("a😀b", 1)
            assertTrue(connection.deleteSurroundingTextInCodePoints(2, 0))
            assertEquals("a", connection.composition.text)
            connection.closeConnection()
            assertFalse(connection.commitText("한", 1))
            assertFalse(connection.setComposingText("글", 1))
            assertFalse(connection.deleteSurroundingText(1, 0))
            assertEquals("", connection.getTextBeforeCursor(100, 0))
            assertEquals("", sink.text.toString())
        }
    }
}
