// Pure terminal input transformation: key sequences and the Ctrl and Alt
// modifiers.
//
// The rules here are the ones the previous per-screen copies got wrong. Ctrl
// used to take the first byte of whatever was committed, which truncates a
// multibyte CJK commit to a meaningless control byte and corrupts the input.

export type ModifierKey = 'ctrl' | 'alt';

/** A logical extra key. */
export type TerminalKey =
  | 'esc'
  | 'tab'
  | 'up'
  | 'down'
  | 'left'
  | 'right'
  | 'home'
  | 'end'
  | 'pageup'
  | 'pagedown'
  | 'slash'
  | 'pipe'
  | 'backslash';

const SEQUENCES: Record<TerminalKey, readonly number[]> = {
  esc: [0x1b],
  tab: [0x09],
  up: [0x1b, 0x5b, 0x41],
  down: [0x1b, 0x5b, 0x42],
  right: [0x1b, 0x5b, 0x43],
  left: [0x1b, 0x5b, 0x44],
  home: [0x1b, 0x5b, 0x48],
  end: [0x1b, 0x5b, 0x46],
  pageup: [0x1b, 0x5b, 0x35, 0x7e],
  pagedown: [0x1b, 0x5b, 0x36, 0x7e],
  slash: [0x2f],
  pipe: [0x7c],
  backslash: [0x5c],
};

/**
 * The xterm modifier parameter, as used in CSI 1;<n><final>.
 *
 * The encoding is a bitfield offset by one: shift 1, alt 2, ctrl 4.
 */
const MODIFIER_PARAM: Record<ModifierKey, number> = {
  alt: 3, // 1 + 2
  ctrl: 5, // 1 + 4
};

/**
 * The final byte of a CSI cursor or editing sequence, when the key has one.
 *
 * These are the keys a terminal expects to receive with a modifier parameter
 * rather than with an ESC prefix or a control byte.
 */
const CSI_FINAL: Partial<Record<TerminalKey, number>> = {
  up: 0x41,
  down: 0x42,
  right: 0x43,
  left: 0x44,
  home: 0x48,
  end: 0x46,
};

/** Keys sent as CSI <n> ~ , which take their modifier in a second parameter. */
const CSI_TILDE: Partial<Record<TerminalKey, number>> = {
  pageup: 0x35, // 5~
  pagedown: 0x36, // 6~
};

/**
 * Encodes a modified cursor or editing key.
 *
 * Returns null when the key has no CSI form, so the caller falls back to the
 * ordinary ESC-prefix or control-byte handling.
 */
function modifiedKeySequence(
  key: TerminalKey,
  modifier: ModifierKey,
): Uint8Array | null {
  const param = MODIFIER_PARAM[modifier];
  const final = CSI_FINAL[key];
  if (final !== undefined) {
    // CSI 1 ; <param> <final>
    return Uint8Array.from([0x1b, 0x5b, 0x31, 0x3b, 0x30 + param, final]);
  }
  const tilde = CSI_TILDE[key];
  if (tilde !== undefined) {
    // CSI <n> ; <param> ~
    return Uint8Array.from([0x1b, 0x5b, tilde, 0x3b, 0x30 + param, 0x7e]);
  }
  return null;
}

/** Raw bytes for a logical key, or null when the key is unknown. */
export function keySequence(key: string): Uint8Array | null {
  const seq = SEQUENCES[key as TerminalKey];
  return seq === undefined ? null : Uint8Array.from(seq);
}

/**
 * Maps an ASCII byte to its control code (Ctrl+C to 0x03).
 *
 * Only defined for bytes Ctrl actually applies to; callers must check
 * `ctrlApplies` first.
 */
export function ctrlCode(b: number): number {
  if (b >= 0x41 && b <= 0x5a) return b - 0x40; // A-Z
  if (b >= 0x61 && b <= 0x7a) return b - 0x60; // a-z
  if (b === 0x40) return 0x00; // @
  if (b === 0x5b) return 0x1b; // [
  if (b === 0x5c) return 0x1c; // backslash
  if (b === 0x5d) return 0x1d; // ]
  if (b === 0x5e) return 0x1e; // ^
  if (b === 0x5f) return 0x1f; // _
  if (b === 0x20) return 0x00; // space
  if (b === 0x3f) return 0x7f; // ? to DEL
  return b & 0x7f;
}

/**
 * True when Ctrl can be applied to this committed input.
 *
 * Ctrl is only meaningful for a single ASCII character. A multibyte commit,
 * such as a Hangul syllable, has no control equivalent.
 */
export function ctrlApplies(bytes: Uint8Array): boolean {
  return bytes.length === 1 && bytes[0] < 0x80;
}

export interface TransformResult {
  /** The bytes to send. */
  bytes: Uint8Array;
  /** True when the latch was consumed and should be cleared. */
  clearModifier: boolean;
  /**
   * Set when the modifier could not be applied. The caller shows this once and
   * sends the input unchanged, so the keystroke is never silently eaten.
   */
  notice?: string;
}

const CTRL_ASCII_ONLY = 'Ctrl applies to ASCII keys';

/**
 * Applies a latched modifier to committed input.
 *
 * Ctrl on a non-ASCII commit sends the text unchanged, clears the latch, and
 * returns a notice. Alt prefixes ESC to the complete byte sequence, which is
 * well defined for any commit including CJK.
 */
export function applyModifier(
  bytes: Uint8Array,
  modifier: ModifierKey | null,
): TransformResult {
  if (modifier === null || bytes.length === 0) {
    return { bytes, clearModifier: false };
  }
  if (modifier === 'alt') {
    const out = new Uint8Array(bytes.length + 1);
    out[0] = 0x1b;
    out.set(bytes, 1);
    return { bytes: out, clearModifier: true };
  }
  if (!ctrlApplies(bytes)) {
    return { bytes, clearModifier: true, notice: CTRL_ASCII_ONLY };
  }
  return {
    bytes: Uint8Array.from([ctrlCode(bytes[0])]),
    clearModifier: true,
  };
}

/**
 * Builds the bytes for an extra-key press, applying a latched modifier.
 *
 * Returns null for an unknown key so the caller can ignore it rather than
 * sending something arbitrary to a live shell.
 */
export function transformKey(
  key: string,
  modifier: ModifierKey | null,
): TransformResult | null {
  const seq = keySequence(key);
  if (seq === null) return null;
  if (modifier === null) return { bytes: seq, clearModifier: false };

  // A cursor or editing key carries its modifier inside the CSI sequence.
  // Prefixing ESC instead produced a doubled escape (ESC ESC [ A), which a
  // terminal reads as Escape followed by an unmodified arrow; applying Ctrl
  // byte-wise mangled the sequence into a single control code.
  const modified = modifiedKeySequence(key as TerminalKey, modifier);
  if (modified !== null) return { bytes: modified, clearModifier: true };

  return applyModifier(seq, modifier);
}
