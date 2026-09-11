package com.remotly.app.ui.terminal

// Pure terminal input transformation: key sequences and the Ctrl and Alt
// modifiers.
//
// The rules here are the ones a per-screen copy could easily get wrong. Ctrl
// must never take the first byte of whatever was committed: that truncates a
// multibyte CJK commit to a meaningless control byte and corrupts the input.

/** A latched input modifier. */
enum class ModifierKey { CTRL, ALT }

private val SEQUENCES: Map<String, IntArray> = mapOf(
    "esc" to intArrayOf(0x1b),
    "tab" to intArrayOf(0x09),
    "up" to intArrayOf(0x1b, 0x5b, 0x41),
    "down" to intArrayOf(0x1b, 0x5b, 0x42),
    "right" to intArrayOf(0x1b, 0x5b, 0x43),
    "left" to intArrayOf(0x1b, 0x5b, 0x44),
    "home" to intArrayOf(0x1b, 0x5b, 0x48),
    "end" to intArrayOf(0x1b, 0x5b, 0x46),
    "pageup" to intArrayOf(0x1b, 0x5b, 0x35, 0x7e),
    "pagedown" to intArrayOf(0x1b, 0x5b, 0x36, 0x7e),
    "slash" to intArrayOf(0x2f),
    "pipe" to intArrayOf(0x7c),
    "backslash" to intArrayOf(0x5c),
)

/**
 * The xterm modifier parameter, as used in CSI 1;<n><final>.
 *
 * The encoding is a bitfield offset by one: shift 1, alt 2, ctrl 4.
 */
private val MODIFIER_PARAM: Map<ModifierKey, Int> = mapOf(
    ModifierKey.ALT to 3, // 1 + 2
    ModifierKey.CTRL to 5, // 1 + 4
)

/**
 * The final byte of a CSI cursor or editing sequence, when the key has one.
 *
 * These are the keys a terminal expects to receive with a modifier
 * parameter, rather than with an ESC prefix or a control byte.
 */
private val CSI_FINAL: Map<String, Int> = mapOf(
    "up" to 0x41,
    "down" to 0x42,
    "right" to 0x43,
    "left" to 0x44,
    "home" to 0x48,
    "end" to 0x46,
)

/** Keys sent as CSI <n> ~, which take their modifier in a second parameter. */
private val CSI_TILDE: Map<String, Int> = mapOf(
    "pageup" to 0x35, // 5~
    "pagedown" to 0x36, // 6~
)

/** Raw bytes for a logical key, or null when the key is unknown. */
fun keySequence(key: String): ByteArray? =
    SEQUENCES[key]?.let { seq -> ByteArray(seq.size) { i -> seq[i].toByte() } }

/**
 * Maps an ASCII byte to its control code (Ctrl+C to 0x03).
 *
 * Only defined for bytes Ctrl actually applies to; callers must check
 * [ctrlApplies] first.
 */
fun ctrlCode(b: Int): Int = when {
    b in 0x41..0x5a -> b - 0x40 // A-Z
    b in 0x61..0x7a -> b - 0x60 // a-z
    b == 0x40 -> 0x00 // @
    b == 0x5b -> 0x1b // [
    b == 0x5c -> 0x1c // backslash
    b == 0x5d -> 0x1d // ]
    b == 0x5e -> 0x1e // ^
    b == 0x5f -> 0x1f // _
    b == 0x20 -> 0x00 // space
    b == 0x3f -> 0x7f // ? to DEL
    else -> b and 0x7f
}

/**
 * True when Ctrl can be applied to this committed input.
 *
 * Ctrl is only meaningful for a single ASCII character. A multibyte commit,
 * such as a Hangul syllable, has no control equivalent.
 */
fun ctrlApplies(bytes: ByteArray): Boolean =
    bytes.size == 1 && (bytes[0].toInt() and 0xff) < 0x80

/** The outcome of applying a modifier to committed input or a key press. */
data class TransformResult(
    /** The bytes to send. */
    val bytes: ByteArray,
    /** True when the latch was consumed and should be cleared. */
    val clearModifier: Boolean,
    /**
     * Set when the modifier could not be applied. The caller shows this once
     * and sends the input unchanged, so the keystroke is never silently
     * eaten.
     */
    val notice: String? = null,
)

private const val CTRL_ASCII_ONLY = "Ctrl applies to ASCII keys"

/**
 * Applies a latched modifier to committed input.
 *
 * Ctrl on a non-ASCII commit sends the text unchanged, clears the latch, and
 * returns a notice. Alt prefixes ESC to the complete byte sequence, which is
 * well defined for any commit including CJK.
 */
fun applyModifier(bytes: ByteArray, modifier: ModifierKey?): TransformResult {
    if (modifier == null || bytes.isEmpty()) {
        return TransformResult(bytes, clearModifier = false)
    }
    if (modifier == ModifierKey.ALT) {
        val out = ByteArray(bytes.size + 1)
        out[0] = 0x1b
        bytes.copyInto(out, destinationOffset = 1)
        return TransformResult(out, clearModifier = true)
    }
    if (!ctrlApplies(bytes)) {
        return TransformResult(bytes, clearModifier = true, notice = CTRL_ASCII_ONLY)
    }
    val code = ctrlCode(bytes[0].toInt() and 0xff)
    return TransformResult(byteArrayOf(code.toByte()), clearModifier = true)
}

/**
 * Encodes a modified cursor or editing key.
 *
 * Returns null when the key has no CSI form, so the caller falls back to the
 * ordinary ESC-prefix or control-byte handling.
 */
private fun modifiedKeySequence(key: String, modifier: ModifierKey): ByteArray? {
    val param = MODIFIER_PARAM.getValue(modifier)
    val final = CSI_FINAL[key]
    if (final != null) {
        // CSI 1 ; <param> <final>
        return byteArrayOf(0x1b, 0x5b, 0x31, 0x3b, (0x30 + param).toByte(), final.toByte())
    }
    val tilde = CSI_TILDE[key]
    if (tilde != null) {
        // CSI <n> ; <param> ~
        return byteArrayOf(0x1b, 0x5b, tilde.toByte(), 0x3b, (0x30 + param).toByte(), 0x7e)
    }
    return null
}

/**
 * Builds the bytes for an extra-key press, applying a latched modifier.
 *
 * Returns null for an unknown key so the caller can ignore it rather than
 * sending something arbitrary to a live shell.
 */
fun transformKey(key: String, modifier: ModifierKey?): TransformResult? {
    val seq = keySequence(key) ?: return null
    if (modifier == null) return TransformResult(seq, clearModifier = false)

    // A cursor or editing key carries its modifier inside the CSI sequence.
    // Prefixing ESC instead produces a doubled escape (ESC ESC [ A), which a
    // terminal reads as Escape followed by an unmodified arrow; applying
    // Ctrl byte-wise would instead mangle the sequence into a single control
    // code.
    val modified = modifiedKeySequence(key, modifier)
    if (modified != null) return TransformResult(modified, clearModifier = true)

    return applyModifier(seq, modifier)
}
