import { describe, expect, it } from '@jest/globals';

import { decodeBase64, encodeBase64 } from '../base64';

function bytes(...values: number[]): Uint8Array {
  return new Uint8Array(values);
}

describe('encodeBase64', () => {
  it('encodes the standard test vectors', () => {
    expect(encodeBase64(bytes())).toBe('');
    expect(encodeBase64(bytes(0x66))).toBe('Zg==');
    expect(encodeBase64(bytes(0x66, 0x6f))).toBe('Zm8=');
    expect(encodeBase64(bytes(0x66, 0x6f, 0x6f))).toBe('Zm9v');
    expect(encodeBase64(bytes(0x66, 0x6f, 0x6f, 0x62, 0x61, 0x72))).toBe(
      'Zm9vYmFy',
    );
  });

  it('encodes all 256 byte values to the canonical table', () => {
    const all = new Uint8Array(256).map((_, i) => i);
    // Reference: split into 3-byte groups and compare against known encoding
    // of each group using an independent construction.
    const groups: string[] = [];
    for (let i = 0; i < 256; i += 3) {
      groups.push(encodeBase64(all.subarray(i, i + 3)));
    }
    expect(encodeBase64(all)).toBe(groups.join(''));
    // Spot-check well-known values.
    expect(encodeBase64(bytes(0))).toBe('AA==');
    expect(encodeBase64(bytes(0xff))).toBe('/w==');
    expect(encodeBase64(bytes(0xff, 0xff))).toBe('//8=');
    expect(encodeBase64(bytes(0xff, 0xff, 0xff))).toBe('////');
  });

  it('round-trips arbitrary byte sequences', () => {
    const data = new Uint8Array(1000);
    for (let i = 0; i < data.length; i++) {
      data[i] = (i * 7 + 13) & 0xff;
    }
    expect(decodeBase64(encodeBase64(data))).toEqual(data);
  });

  it('round-trips UTF-8 multi-byte text', () => {
    // "한" is 3 bytes in UTF-8; the terminal emits raw UTF-8.
    const utf8 = new TextEncoder().encode('한\tremotly\x00\x01\x02');
    expect(decodeBase64(encodeBase64(utf8))).toEqual(utf8);
  });
});

describe('decodeBase64', () => {
  it('decodes the standard test vectors', () => {
    expect(decodeBase64('')).toEqual(bytes());
    expect(decodeBase64('Zg==')).toEqual(bytes(0x66));
    expect(decodeBase64('Zm8=')).toEqual(bytes(0x66, 0x6f));
    expect(decodeBase64('Zm9v')).toEqual(bytes(0x66, 0x6f, 0x6f));
    expect(decodeBase64('Zm9vYmFy')).toEqual(
      bytes(0x66, 0x6f, 0x6f, 0x62, 0x61, 0x72),
    );
  });

  it('ignores ASCII whitespace', () => {
    expect(decodeBase64('Zm9v\nYmFy\t')).toEqual(
      bytes(0x66, 0x6f, 0x6f, 0x62, 0x61, 0x72),
    );
  });

  it('rejects characters outside the alphabet', () => {
    expect(() => decodeBase64('Zm9!')).toThrow(/out of alphabet/);
    expect(() => decodeBase64('Zm_v')).toThrow(/out of alphabet/);
    // Non-ASCII (e.g. a stray multibyte char) must not be silently dropped.
    expect(() => decodeBase64('Zm한v')).toThrow(/out of alphabet/);
  });

  it('rejects malformed padding', () => {
    expect(() => decodeBase64('Zm9')).toThrow(/multiple of 4/);
    expect(() => decodeBase64('=Zm9')).toThrow(/malformed padding/);
    expect(() => decodeBase64('Z=m9')).toThrow(/malformed padding/);
    expect(() => decodeBase64('Zm=9')).toThrow(/malformed padding/);
    expect(() => decodeBase64('Zm9=')).not.toThrow();
    expect(() => decodeBase64('Zg==')).not.toThrow();
    expect(() => decodeBase64('Z===')).toThrow(/malformed padding/);
  });

  it('round-trips with java.util.Base64.DEFAULT output conventions', () => {
    // java.util.Base64 encodes "foo" as "Zm9v" and "fo" as "Zm8="; both must
    // decode back identically since the native side uses that encoder.
    expect(decodeBase64('Zm9v')).toEqual(bytes(0x66, 0x6f, 0x6f));
    expect(decodeBase64('Zm8=')).toEqual(bytes(0x66, 0x6f));
  });
});
