import {
  applyModifier,
  ctrlApplies,
  ctrlCode,
  keySequence,
  transformKey,
} from '../terminalInput';

const utf8 = (s: string): Uint8Array =>
  Uint8Array.from(Array.from(s).flatMap(ch => encodeChar(ch)));

// Minimal UTF-8 encoder: the runtime under test has no TextEncoder.
function encodeChar(ch: string): number[] {
  const cp = ch.codePointAt(0) ?? 0;
  if (cp < 0x80) return [cp];
  if (cp < 0x800) return [0xc0 | (cp >> 6), 0x80 | (cp & 0x3f)];
  if (cp < 0x10000) {
    return [0xe0 | (cp >> 12), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f)];
  }
  return [
    0xf0 | (cp >> 18),
    0x80 | ((cp >> 12) & 0x3f),
    0x80 | ((cp >> 6) & 0x3f),
    0x80 | (cp & 0x3f),
  ];
}

describe('ctrlCode', () => {
  it('maps letters to control codes in both cases', () => {
    expect(ctrlCode(0x41)).toBe(0x01); // Ctrl+A
    expect(ctrlCode(0x61)).toBe(0x01); // Ctrl+a
    expect(ctrlCode(0x43)).toBe(0x03); // Ctrl+C
    expect(ctrlCode(0x63)).toBe(0x03); // Ctrl+c
  });

  it('maps the punctuation control codes', () => {
    expect(ctrlCode(0x40)).toBe(0x00); // Ctrl+@
    expect(ctrlCode(0x5b)).toBe(0x1b); // Ctrl+[ is ESC
    expect(ctrlCode(0x5c)).toBe(0x1c); // Ctrl+backslash
    expect(ctrlCode(0x5d)).toBe(0x1d);
    expect(ctrlCode(0x5f)).toBe(0x1f);
    expect(ctrlCode(0x20)).toBe(0x00); // Ctrl+space
    expect(ctrlCode(0x3f)).toBe(0x7f); // Ctrl+? is DEL
  });
});

describe('ctrlApplies', () => {
  it('accepts one ASCII byte', () => {
    expect(ctrlApplies(utf8('a'))).toBe(true);
  });

  it('rejects a multibyte commit', () => {
    expect(ctrlApplies(utf8('한'))).toBe(false);
    expect(ctrlApplies(utf8('あ'))).toBe(false);
  });

  it('rejects an empty or multi-character commit', () => {
    expect(ctrlApplies(new Uint8Array())).toBe(false);
    expect(ctrlApplies(utf8('ab'))).toBe(false);
  });
});

describe('applyModifier', () => {
  it('passes input through with no modifier', () => {
    const result = applyModifier(utf8('a'), null);
    expect(Array.from(result.bytes)).toEqual([0x61]);
    expect(result.clearModifier).toBe(false);
  });

  it('applies Ctrl to a single ASCII key and clears the latch', () => {
    const result = applyModifier(utf8('c'), 'ctrl');
    expect(Array.from(result.bytes)).toEqual([0x03]);
    expect(result.clearModifier).toBe(true);
    expect(result.notice).toBeUndefined();
  });

  it('sends a Hangul commit unchanged rather than truncating it to one byte', () => {
    const hangul = utf8('한');
    const result = applyModifier(hangul, 'ctrl');
    expect(Array.from(result.bytes)).toEqual(Array.from(hangul));
    expect(result.bytes.length).toBe(3);
    expect(result.clearModifier).toBe(true);
    expect(result.notice).toBe('Ctrl applies to ASCII keys');
  });

  it('prefixes ESC for Alt on ASCII', () => {
    const result = applyModifier(utf8('b'), 'alt');
    expect(Array.from(result.bytes)).toEqual([0x1b, 0x62]);
    expect(result.clearModifier).toBe(true);
  });

  it('prefixes ESC to a complete CJK commit for Alt', () => {
    const hangul = utf8('한');
    const result = applyModifier(hangul, 'alt');
    expect(Array.from(result.bytes)).toEqual([0x1b, ...Array.from(hangul)]);
    expect(result.clearModifier).toBe(true);
  });

  it('leaves an empty commit alone', () => {
    const result = applyModifier(new Uint8Array(), 'ctrl');
    expect(result.bytes.length).toBe(0);
    expect(result.clearModifier).toBe(false);
  });
});

describe('keySequence', () => {
  it('returns the escape sequences for editing keys', () => {
    expect(Array.from(keySequence('esc') ?? [])).toEqual([0x1b]);
    expect(Array.from(keySequence('tab') ?? [])).toEqual([0x09]);
    expect(Array.from(keySequence('up') ?? [])).toEqual([0x1b, 0x5b, 0x41]);
    expect(Array.from(keySequence('down') ?? [])).toEqual([0x1b, 0x5b, 0x42]);
    expect(Array.from(keySequence('right') ?? [])).toEqual([0x1b, 0x5b, 0x43]);
    expect(Array.from(keySequence('left') ?? [])).toEqual([0x1b, 0x5b, 0x44]);
    expect(Array.from(keySequence('home') ?? [])).toEqual([0x1b, 0x5b, 0x48]);
    expect(Array.from(keySequence('end') ?? [])).toEqual([0x1b, 0x5b, 0x46]);
    expect(Array.from(keySequence('pageup') ?? [])).toEqual([
      0x1b, 0x5b, 0x35, 0x7e,
    ]);
    expect(Array.from(keySequence('pagedown') ?? [])).toEqual([
      0x1b, 0x5b, 0x36, 0x7e,
    ]);
  });

  it('includes slash, pipe, and backslash', () => {
    expect(Array.from(keySequence('slash') ?? [])).toEqual([0x2f]);
    expect(Array.from(keySequence('pipe') ?? [])).toEqual([0x7c]);
    expect(Array.from(keySequence('backslash') ?? [])).toEqual([0x5c]);
  });

  it('returns null for an unknown key', () => {
    expect(keySequence('nope')).toBeNull();
  });
});

describe('transformKey', () => {
  it('applies Ctrl to a single-byte key', () => {
    const result = transformKey('slash', 'ctrl');
    expect(result).not.toBeNull();
    expect(Array.from(result?.bytes ?? [])).toEqual([ctrlCode(0x2f)]);
    expect(result?.clearModifier).toBe(true);
  });

  // A cursor key carries its modifier inside the CSI sequence. Prefixing ESC
  // produced ESC ESC [ A, which a terminal reads as Escape followed by an
  // unmodified arrow: the stray Escape left vim and shells in normal mode.
  it('encodes Alt on a cursor key as a CSI modifier', () => {
    const result = transformKey('up', 'alt');
    // CSI 1 ; 3 A
    expect(Array.from(result?.bytes ?? [])).toEqual([
      0x1b, 0x5b, 0x31, 0x3b, 0x33, 0x41,
    ]);
    expect(result?.clearModifier).toBe(true);
    expect(result?.notice).toBeUndefined();
  });

  it('encodes Ctrl on a cursor key as a CSI modifier', () => {
    const result = transformKey('up', 'ctrl');
    // CSI 1 ; 5 A
    expect(Array.from(result?.bytes ?? [])).toEqual([
      0x1b, 0x5b, 0x31, 0x3b, 0x35, 0x41,
    ]);
    expect(result?.clearModifier).toBe(true);
    // No longer a failure to report: the modifier applies cleanly.
    expect(result?.notice).toBeUndefined();
  });

  it('encodes every cursor and editing key with its modifier', () => {
    const finals: Record<string, number> = {
      up: 0x41,
      down: 0x42,
      right: 0x43,
      left: 0x44,
      end: 0x46,
      home: 0x48,
    };
    for (const [key, final] of Object.entries(finals)) {
      expect(Array.from(transformKey(key, 'alt')?.bytes ?? [])).toEqual([
        0x1b,
        0x5b,
        0x31,
        0x3b,
        0x33,
        final,
      ]);
    }
  });

  it('encodes page keys with a second parameter', () => {
    // CSI 5 ; 3 ~
    expect(Array.from(transformKey('pageup', 'alt')?.bytes ?? [])).toEqual([
      0x1b, 0x5b, 0x35, 0x3b, 0x33, 0x7e,
    ]);
    expect(Array.from(transformKey('pagedown', 'ctrl')?.bytes ?? [])).toEqual([
      0x1b, 0x5b, 0x36, 0x3b, 0x35, 0x7e,
    ]);
  });

  // Escape and Tab have no CSI form, so they keep the ESC-prefix behavior.
  it('still prefixes ESC for Alt on a key with no CSI form', () => {
    expect(Array.from(transformKey('esc', 'alt')?.bytes ?? [])).toEqual([
      0x1b, 0x1b,
    ]);
    expect(Array.from(transformKey('tab', 'alt')?.bytes ?? [])).toEqual([
      0x1b, 0x09,
    ]);
  });

  it('sends an unmodified key unchanged', () => {
    const result = transformKey('up', null);
    expect(Array.from(result?.bytes ?? [])).toEqual([0x1b, 0x5b, 0x41]);
    expect(result?.clearModifier).toBe(false);
  });

  it('returns null for an unknown key', () => {
    expect(transformKey('nope', null)).toBeNull();
  });
});
