import { describe, expect, it } from '@jest/globals';
import {
  MAX_PREVIEW_BYTES,
  previewByteLength,
  sanitizePreview,
} from '../preview';

// The app re-sanitizes daemon previews as defense in depth. These vectors
// mirror the daemon's preview tests so both sides agree on the contract.

describe('sanitizePreview', () => {
  it('returns empty for empty input', () => {
    expect(sanitizePreview('')).toBe('');
  });

  it('strips CSI sequences', () => {
    expect(sanitizePreview('\u001b[31mred\u001b[0m text')).toBe('red text');
    expect(sanitizePreview('\u001b[1;32;40mbold green on black\u001b[0m')).toBe(
      'bold green on black',
    );
    expect(sanitizePreview('\u001b[?25lhidden\u001b[?25h')).toBe('hidden');
  });

  it('strips OSC strings terminated by BEL and by ST', () => {
    expect(sanitizePreview('\u001b]0;window title\u0007kept')).toBe('kept');
    expect(sanitizePreview('\u001b]2;tab\u001b\\kept')).toBe('kept');
    expect(sanitizePreview('\u001b]8;;https://x\u0007link text')).toBe(
      'link text',
    );
  });

  it('strips two-byte escapes and lone ESC', () => {
    expect(sanitizePreview('\u001b(Btext')).toBe('text'); // charset designation
    // ESC b is a real two-byte escape (DECKPAM): both bytes are consumed.
    expect(sanitizePreview('a\u001bb')).toBe('a');
    expect(sanitizePreview('trailing\u001b')).toBe('trailing');
  });

  it('drops C0 controls but collapses tabs and spaces', () => {
    expect(sanitizePreview('a\u0000b\u0001c\td\te f')).toBe('abc d e f');
    expect(sanitizePreview('\u0007bell\u0008back')).toBe('bellback');
    expect(sanitizePreview('del\u007fhere')).toBe('delhere');
  });

  it('drops C1 controls', () => {
    expect(sanitizePreview('a\u0080b\u009bc\u009bd')).toBe('abcd');
    expect(sanitizePreview('a\u009b[b')).toBe('a[b');
  });

  it('collapses Unicode whitespace to a single space', () => {
    expect(sanitizePreview('a\u00a0\u2003b')).toBe('a b');
    expect(sanitizePreview('\u00a0')).toBe('');
  });

  it('keeps CJK and emoji intact', () => {
    expect(sanitizePreview('hello 世界')).toBe('hello 世界');
    expect(sanitizePreview('x \u{1f600} y')).toBe('x \u{1f600} y');
  });

  it('truncates to the byte limit on a code point boundary', () => {
    // 40 CJK runes * 3 bytes = 120 bytes: fits exactly.
    expect(sanitizePreview('世'.repeat(40))).toHaveLength(40);
    // 41 CJK runes would be 123 bytes: the last rune is dropped.
    expect(sanitizePreview('世'.repeat(41))).toHaveLength(40);
    // An emoji (4 bytes) that would cross the boundary is dropped whole.
    const base = 'a'.repeat(118); // 118 bytes
    expect(sanitizePreview(base + '\u{1f600}')).toBe(base);
    const fit = 'a'.repeat(117) + '\u{1f600}'; // 117 + 4 = 121 > 120: dropped
    expect(sanitizePreview(fit)).toBe('a'.repeat(117));
  });

  it('never exceeds the byte limit', () => {
    for (const s of [
      'x'.repeat(500),
      '世'.repeat(100),
      '\u{1f600}'.repeat(100),
    ]) {
      const out = sanitizePreview(s);
      expect(previewByteLength(out)).toBeLessThanOrEqual(MAX_PREVIEW_BYTES);
    }
  });

  it('trims leading and trailing whitespace from collapsing', () => {
    expect(sanitizePreview('  hello   world  ')).toBe('hello world');
    expect(sanitizePreview('\t \t')).toBe('');
  });

  it('strips escapes that look like text', () => {
    expect(sanitizePreview('\u001b[31')).toBe(''); // unfinished CSI at end
    expect(sanitizePreview('\u001b]title')).toBe(''); // unfinished OSC at end
    expect(sanitizePreview('\u001b[;m')).toBe(''); // empty CSI
  });
});
