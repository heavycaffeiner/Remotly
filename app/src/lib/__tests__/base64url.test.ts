import { describe, expect, it } from '@jest/globals';

import { decodeBase64Url, encodeBase64Url } from '../base64url';

function bytes(...values: number[]): Uint8Array {
  return new Uint8Array(values);
}

describe('encodeBase64Url', () => {
  it('encodes the RFC 4648 test vectors (unpadded)', () => {
    expect(encodeBase64Url(bytes())).toBe('');
    expect(encodeBase64Url(bytes(0x66))).toBe('Zg');
    expect(encodeBase64Url(bytes(0x66, 0x6f))).toBe('Zm8');
    expect(encodeBase64Url(bytes(0x66, 0x6f, 0x6f))).toBe('Zm9v');
    expect(encodeBase64Url(bytes(0x66, 0x6f, 0x6f, 0x62, 0x61, 0x72))).toBe(
      'Zm9vYmFy',
    );
  });

  it('uses the URL alphabet for high bits', () => {
    expect(encodeBase64Url(bytes(0xfb, 0xff, 0xfe))).toBe('-__-');
    expect(encodeBase64Url(bytes(0xfb))).toBe('-w');
    expect(encodeBase64Url(bytes(0xfb, 0xff))).toBe('-_8');
  });

  it('round-trips arbitrary byte sequences of every length class', () => {
    for (let len = 0; len < 16; len++) {
      const data = new Uint8Array(len);
      for (let i = 0; i < len; i++) data[i] = (i * 31 + 7) & 0xff;
      expect(decodeBase64Url(encodeBase64Url(data))).toEqual(data);
    }
    const big = new Uint8Array(4096);
    for (let i = 0; i < big.length; i++) big[i] = (i * 7 + 13) & 0xff;
    expect(decodeBase64Url(encodeBase64Url(big))).toEqual(big);
  });
});

describe('decodeBase64Url', () => {
  it('decodes the RFC 4648 test vectors', () => {
    expect(decodeBase64Url('')).toEqual(bytes());
    expect(decodeBase64Url('Zg')).toEqual(bytes(0x66));
    expect(decodeBase64Url('Zm8')).toEqual(bytes(0x66, 0x6f));
    expect(decodeBase64Url('Zm9v')).toEqual(bytes(0x66, 0x6f, 0x6f));
  });

  it('rejects padding', () => {
    expect(() => decodeBase64Url('Zg==')).toThrow();
    expect(() => decodeBase64Url('Zm8=')).toThrow();
    expect(() => decodeBase64Url('Zg=')).toThrow();
  });

  it('rejects the standard alphabet', () => {
    expect(() => decodeBase64Url('ab+c')).toThrow();
    expect(() => decodeBase64Url('ab/c')).toThrow();
  });

  it('rejects impossible lengths (1 mod 4)', () => {
    expect(() => decodeBase64Url('Z')).toThrow();
    expect(() => decodeBase64Url('Zm9vY')).toThrow();
  });

  it('rejects whitespace and other out-of-alphabet characters', () => {
    expect(() => decodeBase64Url('Zg ')).toThrow();
    expect(() => decodeBase64Url('Zg\n')).toThrow();
    expect(() => decodeBase64Url('Zg~')).toThrow();
    expect(() => decodeBase64Url('Zg=0')).toThrow();
  });

  it('rejects non-ASCII characters', () => {
    expect(() => decodeBase64Url('Zg\u00e9')).toThrow();
  });
});
