// Base64 for raw terminal bytes crossing the React Native bridge.
//
// The native terminal component speaks bytes: `write` accepts a base64 string
// and the `input`/`ptywrite` events deliver base64. The JS side (the transport
// and the SSH session) speaks raw bytes (Uint8Array). This module is the only
// place that converts between the two, so neither the session nor the viewport
// touches base64 directly.
//
// It is self-contained (no btoa/atob/TextEncoder dependency on Hermes) and
// validates strictly, because a silently corrupted byte sequence would corrupt
// terminal output or inject bytes into a session.

const ALPHABET =
  'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

// Lookup table: byte value -> 6-bit index, or -1 when the character is not in
// the alphabet. Indexed by char code so decoding is a flat array read.
const INDEX = new Int8Array(128).fill(-1);
for (let i = 0; i < ALPHABET.length; i++) {
  INDEX[ALPHABET.charCodeAt(i)] = i;
}

// UTF-8 encodes a string to bytes. Hand-rolled because Hermes (and the RN
// tsconfig, which omits the dom lib) provides no TextEncoder. Output matches
// the encoder in files.ts byte for byte.
function utf8(s: string): Uint8Array {
  const out: number[] = [];
  for (let i = 0; i < s.length; i++) {
    let cp = s.charCodeAt(i);
    if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < s.length) {
      const lo = s.charCodeAt(i + 1);
      if (lo >= 0xdc00 && lo <= 0xdfff) {
        cp = 0x10000 + ((cp - 0xd800) << 10) + (lo - 0xdc00);
        i++;
      }
    }
    if (cp < 0x80) {
      out.push(cp);
    } else if (cp < 0x800) {
      out.push(0xc0 | (cp >> 6), 0x80 | (cp & 0x3f));
    } else if (cp < 0x10000) {
      out.push(
        0xe0 | (cp >> 12),
        0x80 | ((cp >> 6) & 0x3f),
        0x80 | (cp & 0x3f),
      );
    } else {
      out.push(
        0xf0 | (cp >> 18),
        0x80 | ((cp >> 12) & 0x3f),
        0x80 | ((cp >> 6) & 0x3f),
        0x80 | (cp & 0x3f),
      );
    }
  }
  return Uint8Array.from(out);
}

// Base64-encodes a string after UTF-8 encoding it. Carries a PEM private key
// across the bridge, where the native side expects standard base64.
export function encodeBase64String(s: string): string {
  return encodeBase64(utf8(s));
}

export function encodeBase64(bytes: Uint8Array): string {
  const parts: string[] = [];
  const n = bytes.length;
  const full = n - (n % 3);
  for (let i = 0; i < full; i += 3) {
    const n0 = (bytes[i] << 16) | (bytes[i + 1] << 8) | bytes[i + 2];
    parts.push(
      ALPHABET[(n0 >> 18) & 63],
      ALPHABET[(n0 >> 12) & 63],
      ALPHABET[(n0 >> 6) & 63],
      ALPHABET[n0 & 63],
    );
  }
  const rem = n - full;
  if (rem === 1) {
    const b0 = bytes[full];
    parts.push(ALPHABET[(b0 >> 2) & 63], ALPHABET[(b0 << 4) & 63], '=', '=');
  } else if (rem === 2) {
    const b0 = bytes[full];
    const b1 = bytes[full + 1];
    parts.push(
      ALPHABET[(b0 >> 2) & 63],
      ALPHABET[(((b0 & 3) << 4) | (b1 >> 4)) & 63],
      ALPHABET[(b1 << 2) & 63],
      '=',
    );
  }
  return parts.join('');
}

export function decodeBase64(input: string): Uint8Array {
  const s = input.replace(/[\t\n\r ]/g, '');
  const n = s.length;
  if (n === 0) {
    return new Uint8Array(0);
  }
  if (n % 4 !== 0) {
    throw new Error('invalid base64: length is not a multiple of 4');
  }

  // Count trailing padding to size the output exactly.
  let pad = 0;
  if (s[n - 1] === '=') {
    pad++;
    if (s[n - 2] === '=') {
      pad++;
    }
  }
  const out = new Uint8Array((n >> 2) * 3 - pad);
  let op = 0;
  for (let i = 0; i < n; i += 4) {
    const c0 = s.charCodeAt(i);
    const c1 = s.charCodeAt(i + 1);
    const c2 = s.charCodeAt(i + 2);
    const c3 = s.charCodeAt(i + 3);

    if (c0 === 61 || c1 === 61 || (c2 === 61 && c3 !== 61)) {
      throw new Error('invalid base64: malformed padding');
    }

    const v0 = c0 < 128 ? INDEX[c0] : -1;
    const v1 = c1 < 128 ? INDEX[c1] : -1;
    const v2 = c2 === 61 ? -1 : c2 < 128 ? INDEX[c2] : -1;
    const v3 = c3 === 61 ? -1 : c3 < 128 ? INDEX[c3] : -1;

    if (v0 < 0 || v1 < 0 || (c2 !== 61 && v2 < 0) || (c3 !== 61 && v3 < 0)) {
      throw new Error('invalid base64: character out of alphabet');
    }

    const triple =
      (v0 << 18) | (v1 << 12) | (v2 >= 0 ? v2 << 6 : 0) | (v3 >= 0 ? v3 : 0);
    out[op++] = (triple >> 16) & 0xff;
    if (c2 !== 61) {
      out[op++] = (triple >> 8) & 0xff;
    }
    if (c3 !== 61) {
      out[op++] = triple & 0xff;
    }
  }
  return out;
}
