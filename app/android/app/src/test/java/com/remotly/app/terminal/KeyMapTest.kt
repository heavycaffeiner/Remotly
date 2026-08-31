package com.remotly.app.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyMapTest {

  @Test
  fun lettersMapToGhosttyKeys() {
    for (i in 0 until 26) {
      assertEquals(
        "letter $i",
        KeyMap.KEY_A + i,
        KeyMap.keyCodeToGhostty(KeyEvent.KEYCODE_A + i),
      )
    }
  }

  @Test
  fun digitsMapToGhosttyKeys() {
    for (i in 0 until 10) {
      assertEquals(
        "digit $i",
        KeyMap.KEY_DIGIT_0 + i,
        KeyMap.keyCodeToGhostty(KeyEvent.KEYCODE_0 + i),
      )
    }
  }

  @Test
  fun functionKeysMapToGhosttyKeys() {
    for (i in 0 until 12) {
      assertEquals(
        "F${i + 1}",
        KeyMap.KEY_F1 + i,
        KeyMap.keyCodeToGhostty(KeyEvent.KEYCODE_F1 + i),
      )
    }
  }

  @Test
  fun specialKeysMapToGhosttyKeys() {
    val cases = mapOf(
      KeyEvent.KEYCODE_ENTER to KeyMap.KEY_ENTER,
      KeyEvent.KEYCODE_DEL to KeyMap.KEY_BACKSPACE,
      KeyEvent.KEYCODE_FORWARD_DEL to KeyMap.KEY_DELETE,
      KeyEvent.KEYCODE_ESCAPE to KeyMap.KEY_ESCAPE,
      KeyEvent.KEYCODE_TAB to KeyMap.KEY_TAB,
      KeyEvent.KEYCODE_SPACE to KeyMap.KEY_SPACE,
      KeyEvent.KEYCODE_DPAD_UP to KeyMap.KEY_ARROW_UP,
      KeyEvent.KEYCODE_DPAD_DOWN to KeyMap.KEY_ARROW_DOWN,
      KeyEvent.KEYCODE_DPAD_LEFT to KeyMap.KEY_ARROW_LEFT,
      KeyEvent.KEYCODE_DPAD_RIGHT to KeyMap.KEY_ARROW_RIGHT,
      KeyEvent.KEYCODE_MOVE_HOME to KeyMap.KEY_HOME,
      KeyEvent.KEYCODE_MOVE_END to KeyMap.KEY_END,
      KeyEvent.KEYCODE_PAGE_UP to KeyMap.KEY_PAGE_UP,
      KeyEvent.KEYCODE_PAGE_DOWN to KeyMap.KEY_PAGE_DOWN,
      KeyEvent.KEYCODE_INSERT to KeyMap.KEY_INSERT,
      KeyEvent.KEYCODE_SEMICOLON to KeyMap.KEY_SEMICOLON,
      KeyEvent.KEYCODE_COMMA to KeyMap.KEY_COMMA,
      KeyEvent.KEYCODE_PERIOD to KeyMap.KEY_PERIOD,
      KeyEvent.KEYCODE_SLASH to KeyMap.KEY_SLASH,
      KeyEvent.KEYCODE_GRAVE to KeyMap.KEY_BACKQUOTE,
      KeyEvent.KEYCODE_LEFT_BRACKET to KeyMap.KEY_BRACKET_LEFT,
      KeyEvent.KEYCODE_RIGHT_BRACKET to KeyMap.KEY_BRACKET_RIGHT,
      KeyEvent.KEYCODE_AT to KeyMap.KEY_EQUAL,
    )
    for ((android, ghostty) in cases) {
      assertEquals("keyCode $android", ghostty, KeyMap.keyCodeToGhostty(android))
    }
  }

  @Test
  fun unmappedKeysAreUnidentified() {
    assertEquals(KeyMap.KEY_UNIDENTIFIED, KeyMap.keyCodeToGhostty(KeyEvent.KEYCODE_UNKNOWN))
    assertEquals(KeyMap.KEY_UNIDENTIFIED, KeyMap.keyCodeToGhostty(0))
    assertEquals(KeyMap.KEY_UNIDENTIFIED, KeyMap.keyCodeToGhostty(KeyEvent.KEYCODE_DPAD_CENTER))
    assertEquals(KeyMap.KEY_UNIDENTIFIED, KeyMap.keyCodeToGhostty(KeyEvent.KEYCODE_STAR))
  }

  @Test
  fun modifierConstantsMatchGhostty() {
    assertEquals(1 shl 0, KeyMap.MOD_SHIFT)
    assertEquals(1 shl 1, KeyMap.MOD_CTRL)
    assertEquals(1 shl 2, KeyMap.MOD_ALT)
    assertEquals(1 shl 3, KeyMap.MOD_SUPER)
  }
}
