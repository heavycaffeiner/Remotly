package com.remotly.app.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
      KeyEvent.KEYCODE_APOSTROPHE to KeyMap.KEY_QUOTE,
      KeyEvent.KEYCODE_COMMA to KeyMap.KEY_COMMA,
      KeyEvent.KEYCODE_PERIOD to KeyMap.KEY_PERIOD,
      KeyEvent.KEYCODE_SLASH to KeyMap.KEY_SLASH,
      KeyEvent.KEYCODE_GRAVE to KeyMap.KEY_BACKQUOTE,
      KeyEvent.KEYCODE_BACKSLASH to KeyMap.KEY_BACKSLASH,
      KeyEvent.KEYCODE_LEFT_BRACKET to KeyMap.KEY_BRACKET_LEFT,
      KeyEvent.KEYCODE_RIGHT_BRACKET to KeyMap.KEY_BRACKET_RIGHT,
      KeyEvent.KEYCODE_EQUALS to KeyMap.KEY_EQUAL,
      KeyEvent.KEYCODE_AT to KeyMap.KEY_EQUAL,
      KeyEvent.KEYCODE_MINUS to KeyMap.KEY_MINUS,
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
  fun controlIsRemovedOnlyFromCharacterLookup() {
    val layoutMods = KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_ON or
      KeyEvent.META_ALT_RIGHT_ON or KeyEvent.META_ALT_ON or KeyEvent.META_CAPS_LOCK_ON
    val ctrlMods = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_RIGHT_ON
    assertEquals(layoutMods, KeyMap.textMetaState(layoutMods or ctrlMods))
    assertEquals(
      KeyMap.MOD_CTRL or KeyMap.MOD_SHIFT or KeyMap.MOD_ALT,
      KeyMap.modifiersOf(layoutMods or ctrlMods),
    )
  }

  @Test
  fun eitherControlKeyIsRecognizedWithoutTheAggregateBit() {
    for (ctrl in listOf(KeyEvent.META_CTRL_LEFT_ON, KeyEvent.META_CTRL_RIGHT_ON)) {
      assertEquals(KeyMap.MOD_CTRL, KeyMap.modifiersOf(ctrl))
      assertEquals(0, KeyMap.textMetaState(ctrl))
      assertTrue(KeyMap.handlesBeforeIme(KeyEvent.KEYCODE_C, ctrl))
    }
  }

  @Test
  fun escapeAndControlChordsAreHandledBeforeTheIme() {
    assertTrue(KeyMap.handlesBeforeIme(KeyEvent.KEYCODE_ESCAPE, 0))
    assertTrue(KeyMap.handlesBeforeIme(KeyEvent.KEYCODE_CTRL_LEFT, 0))
    assertTrue(KeyMap.handlesBeforeIme(KeyEvent.KEYCODE_CTRL_RIGHT, 0))
    for (key in listOf(KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_LEFT_BRACKET)) {
      assertTrue(KeyMap.handlesBeforeIme(key, KeyEvent.META_CTRL_ON))
    }
  }

  @Test
  fun ordinaryTypingAndSystemControlsStayWithAndroid() {
    assertFalse(KeyMap.handlesBeforeIme(KeyEvent.KEYCODE_A, 0))
    assertFalse(KeyMap.handlesBeforeIme(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON))
    assertFalse(KeyMap.handlesBeforeIme(KeyEvent.KEYCODE_A, KeyEvent.META_ALT_RIGHT_ON))
    for (key in listOf(
      KeyEvent.KEYCODE_BACK,
      KeyEvent.KEYCODE_VOLUME_UP,
      KeyEvent.KEYCODE_VOLUME_DOWN,
      KeyEvent.KEYCODE_VOLUME_MUTE,
      KeyEvent.KEYCODE_POWER,
    )) {
      assertTrue(KeyMap.isSystemKey(key))
      assertFalse(KeyMap.handlesBeforeIme(key, 0))
      assertFalse(KeyMap.handlesBeforeIme(key, KeyEvent.META_CTRL_ON))
    }
  }

  @Test
  fun modifierConstantsMatchGhostty() {
    assertEquals(1 shl 0, KeyMap.MOD_SHIFT)
    assertEquals(1 shl 1, KeyMap.MOD_CTRL)
    assertEquals(1 shl 2, KeyMap.MOD_ALT)
    assertEquals(1 shl 3, KeyMap.MOD_SUPER)
  }
}
