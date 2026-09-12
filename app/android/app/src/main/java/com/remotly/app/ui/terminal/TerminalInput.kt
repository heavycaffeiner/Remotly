package com.remotly.app.ui.terminal

import com.remotly.app.terminal.KeyEncoding
import com.remotly.app.terminal.KeyMap

// Pure terminal input transformation at the native key encoder boundary.
//
// Tool keys and one-shot modifiers must enter through nativeSendKey. Building
// terminal bytes before that boundary bypasses application keyboard modes such
// as Kitty keyboard protocol and cannot safely distinguish typing from paste.

/** A latched input modifier. */
enum class ModifierKey { CTRL, ALT, SHIFT }

private fun modifierMask(modifier: ModifierKey): Int = when (modifier) {
    ModifierKey.CTRL -> KeyMap.MOD_CTRL
    ModifierKey.ALT -> KeyMap.MOD_ALT
    ModifierKey.SHIFT -> KeyMap.MOD_SHIFT
}

private fun shiftAscii(c: Char): Char = when (c) {
    in 'a'..'z' -> c.uppercaseChar()
    '`' -> '~'
    '1' -> '!'
    '2' -> '@'
    '3' -> '#'
    '4' -> '$'
    '5' -> '%'
    '6' -> '^'
    '7' -> '&'
    '8' -> '*'
    '9' -> '('
    '0' -> ')'
    '-' -> '_'
    '=' -> '+'
    '[' -> '{'
    ']' -> '}'
    '\\' -> '|'
    ';' -> ':'
    '\'' -> '"'
    ',' -> '<'
    '.' -> '>'
    '/' -> '?'
    else -> c
}

private fun unshiftAscii(c: Char): Char = when (c) {
    in 'A'..'Z' -> c.lowercaseChar()
    '~' -> '`'
    '!' -> '1'
    '@' -> '2'
    '#' -> '3'
    '$' -> '4'
    '%' -> '5'
    '^' -> '6'
    '&' -> '7'
    '*' -> '8'
    '(' -> '9'
    ')' -> '0'
    '_' -> '-'
    '+' -> '='
    '{' -> '['
    '}' -> ']'
    '|' -> '\\'
    ':' -> ';'
    '"' -> '\''
    '<' -> ','
    '>' -> '.'
    '?' -> '/'
    else -> c
}

/** Native key identity and inherent modifier for one printable ASCII character. */
private fun printableEncoding(c: Char): KeyEncoding? {
    if (c.code !in 0x20..0x7e) return null

    val base = unshiftAscii(c)
    val key = when (base) {
        in 'a'..'z' -> KeyMap.KEY_A + (base - 'a')
        in '0'..'9' -> KeyMap.KEY_DIGIT_0 + (base - '0')
        '`' -> KeyMap.KEY_BACKQUOTE
        '\\' -> KeyMap.KEY_BACKSLASH
        '[' -> KeyMap.KEY_BRACKET_LEFT
        ']' -> KeyMap.KEY_BRACKET_RIGHT
        ',' -> KeyMap.KEY_COMMA
        '=' -> KeyMap.KEY_EQUAL
        '-' -> KeyMap.KEY_MINUS
        '.' -> KeyMap.KEY_PERIOD
        '\'' -> KeyMap.KEY_QUOTE
        ';' -> KeyMap.KEY_SEMICOLON
        '/' -> KeyMap.KEY_SLASH
        ' ' -> KeyMap.KEY_SPACE
        else -> return null
    }
    val mods = if (base == c) 0 else KeyMap.MOD_SHIFT
    return KeyEncoding(key = key, mods = mods, utf8 = c.toString())
}

private fun withModifier(encoding: KeyEncoding, modifier: ModifierKey): KeyEncoding {
    var utf8 = encoding.utf8
    if (modifier == ModifierKey.SHIFT) {
        val c = utf8?.singleOrNull()
        if (c != null && c.code in 0x20..0x7e) utf8 = shiftAscii(c).toString()
    }
    return encoding.copy(mods = encoding.mods or modifierMask(modifier), utf8 = utf8)
}

/**
 * Native key encoding for an extra-key press.
 *
 * The native encoder applies the active terminal protocol. Shift+Tab is a Tab
 * key with the Shift modifier, which becomes ESC [ Z in legacy mode and the
 * negotiated key form in extended keyboard modes.
 */
fun extraKeyEncoding(key: String, modifier: ModifierKey?): KeyEncoding? {
    val base = when (key) {
        "esc" -> KeyEncoding(KeyMap.KEY_ESCAPE, 0, null)
        "tab" -> KeyEncoding(KeyMap.KEY_TAB, 0, null)
        "shift-tab" -> KeyEncoding(KeyMap.KEY_TAB, KeyMap.MOD_SHIFT, null)
        "up" -> KeyEncoding(KeyMap.KEY_ARROW_UP, 0, null)
        "down" -> KeyEncoding(KeyMap.KEY_ARROW_DOWN, 0, null)
        "right" -> KeyEncoding(KeyMap.KEY_ARROW_RIGHT, 0, null)
        "left" -> KeyEncoding(KeyMap.KEY_ARROW_LEFT, 0, null)
        "home" -> KeyEncoding(KeyMap.KEY_HOME, 0, null)
        "end" -> KeyEncoding(KeyMap.KEY_END, 0, null)
        "pageup" -> KeyEncoding(KeyMap.KEY_PAGE_UP, 0, null)
        "pagedown" -> KeyEncoding(KeyMap.KEY_PAGE_DOWN, 0, null)
        "slash" -> printableEncoding('/')
        "pipe" -> printableEncoding('|')
        "backslash" -> printableEncoding('\\')
        else -> null
    } ?: return null
    return if (modifier == null) base else withModifier(base, modifier)
}

/**
 * Encodes one committed printable ASCII character with a latched modifier.
 *
 * Multi-character and non-ASCII commits return null. They have no portable
 * physical key identity, so callers preserve the original text rather than
 * inventing a key or splitting an IME commit.
 */
fun modifiedTextEncoding(text: String, modifier: ModifierKey): KeyEncoding? {
    val c = text.singleOrNull() ?: return null
    val base = printableEncoding(c) ?: return null
    return withModifier(base, modifier)
}

/** Adds a latched modifier to a hardware key already mapped by [KeyMap]. */
fun modifiedHardwareKey(encoding: KeyEncoding, modifier: ModifierKey): KeyEncoding =
    withModifier(encoding, modifier)
