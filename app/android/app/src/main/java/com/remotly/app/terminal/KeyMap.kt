package com.remotly.app.terminal

import android.view.KeyEvent

/** A key event encoded for the ghostty key encoder. */
data class KeyEncoding(val key: Int, val mods: Int, val utf8: String?)

/**
 * Maps Android [KeyEvent]s to ghostty key-encoder inputs. [key] is a
 * GhosttyKey, [mods] a GhosttyMods bitmask, [utf8] the produced character (null
 * for special keys). The native encoder turns these into the correct byte
 * sequence for the terminal's current keyboard modes.
 */
object KeyMap {
  // GhosttyMods
  const val MOD_SHIFT = 1 shl 0
  const val MOD_CTRL = 1 shl 1
  const val MOD_ALT = 1 shl 2
  const val MOD_SUPER = 1 shl 3

  // GhosttyKey (subset used by the mapping; values from
  // include/ghostty/vt/key/event.h at the pinned commit).
  const val KEY_UNIDENTIFIED = 0
  const val KEY_BACKQUOTE = 1
  const val KEY_BACKSLASH = 2
  const val KEY_BRACKET_LEFT = 3
  const val KEY_BRACKET_RIGHT = 4
  const val KEY_COMMA = 5
  const val KEY_DIGIT_0 = 6
  private const val KEY_DIGIT_9 = 15
  const val KEY_EQUAL = 16
  const val KEY_A = 20
  private const val KEY_Z = 45
  const val KEY_PERIOD = 47
  const val KEY_SEMICOLON = 49
  const val KEY_SLASH = 50
  const val KEY_BACKSPACE = 53
  const val KEY_ENTER = 58
  const val KEY_SPACE = 63
  const val KEY_TAB = 64
  const val KEY_DELETE = 68
  const val KEY_END = 69
  const val KEY_HOME = 71
  const val KEY_INSERT = 72
  const val KEY_PAGE_DOWN = 73
  const val KEY_PAGE_UP = 74
  const val KEY_ARROW_DOWN = 75
  const val KEY_ARROW_LEFT = 76
  const val KEY_ARROW_RIGHT = 77
  const val KEY_ARROW_UP = 78
  const val KEY_ESCAPE = 120
  const val KEY_F1 = 121
  private const val KEY_F12 = 132

  fun encode(event: KeyEvent): KeyEncoding {
    var mods = 0
    if (event.isShiftPressed) mods = mods or MOD_SHIFT
    if (event.isCtrlPressed) mods = mods or MOD_CTRL
    if (event.isAltPressed) mods = mods or MOD_ALT
    if (event.metaState and KeyEvent.META_META_ON != 0) mods = mods or MOD_SUPER

    val key = keyCodeToGhostty(event.keyCode)
    val chars = event.getCharacters()
    val utf8 = if (chars.isNullOrEmpty()) null else chars.toString()
    return KeyEncoding(key, mods, utf8)
  }

  // Internal so JVM unit tests can exercise the mapping without a KeyEvent.
  internal fun keyCodeToGhostty(code: Int): Int = when (code) {
    KeyEvent.KEYCODE_ENTER -> KEY_ENTER
    KeyEvent.KEYCODE_DEL -> KEY_BACKSPACE
    KeyEvent.KEYCODE_FORWARD_DEL -> KEY_DELETE
    KeyEvent.KEYCODE_ESCAPE -> KEY_ESCAPE
    KeyEvent.KEYCODE_TAB -> KEY_TAB
    KeyEvent.KEYCODE_SPACE -> KEY_SPACE
    KeyEvent.KEYCODE_DPAD_UP -> KEY_ARROW_UP
    KeyEvent.KEYCODE_DPAD_DOWN -> KEY_ARROW_DOWN
    KeyEvent.KEYCODE_DPAD_LEFT -> KEY_ARROW_LEFT
    KeyEvent.KEYCODE_DPAD_RIGHT -> KEY_ARROW_RIGHT
    KeyEvent.KEYCODE_MOVE_HOME -> KEY_HOME
    KeyEvent.KEYCODE_MOVE_END -> KEY_END
    KeyEvent.KEYCODE_PAGE_UP -> KEY_PAGE_UP
    KeyEvent.KEYCODE_PAGE_DOWN -> KEY_PAGE_DOWN
    KeyEvent.KEYCODE_INSERT -> KEY_INSERT
    KeyEvent.KEYCODE_SEMICOLON -> KEY_SEMICOLON
    KeyEvent.KEYCODE_COMMA -> KEY_COMMA
    KeyEvent.KEYCODE_PERIOD -> KEY_PERIOD
    KeyEvent.KEYCODE_SLASH -> KEY_SLASH
    KeyEvent.KEYCODE_GRAVE -> KEY_BACKQUOTE
    KeyEvent.KEYCODE_LEFT_BRACKET -> KEY_BRACKET_LEFT
    KeyEvent.KEYCODE_RIGHT_BRACKET -> KEY_BRACKET_RIGHT
    KeyEvent.KEYCODE_AT -> KEY_EQUAL
    in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
      KEY_F1 + (code - KeyEvent.KEYCODE_F1)
    in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
      KEY_A + (code - KeyEvent.KEYCODE_A)
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
      KEY_DIGIT_0 + (code - KeyEvent.KEYCODE_0)
    else -> KEY_UNIDENTIFIED
  }
}
