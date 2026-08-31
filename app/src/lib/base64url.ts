// Strict base64url (RFC 4648 section 5, unpadded) codec.
//
// Go's encoding/base64 RawURLEncoding is the wire convention for pairing
// payloads and for the 32-byte keys that travel in transport credentials. This
// codec must accept exactly what RawURLEncoding produces and reject everything
// else (padding, standard-alphabet characters, whitespace, impossible lengths),
// because a silently mis-decoded key means pairing with the wrong daemon.
//
// Self-contained like base64.ts: no btoa/atob dependency on the JS runtime.

const ALPHABET =
  'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';

// Lookup table: char code -> 6-bit index, or -1 outside the alphabet.
const INDEX = new Int8Array(128).fill(-1);
for (let i = 0; i < ALPHABET.length; i++) {
  INDEX[ALPHABET.charCodeAt(i)] = i;
}

function index(code: number): number {
  return code < 128 ? INDEX[code] : -1;
}

export function encodeBase64Url(bytes: Uint8Array): string {
  const parts: string[] = [];
  const n = bytes.length;
  const full = n - (n % 3);
  for (let i = 0; i < full; i += 3) {
    const v = (bytes[i] << 16) | (bytes[i + 1] << 8) | bytes[i + 2];
    parts.push(
      ALPHABET[(v >> 18) & 63],
      ALPHABET[(v >> 12) & 63],
      ALPHABET[(v >> 6) & 63],
      ALPHABET[v & 63],
    );
  }
  const rem = n - full;
  if (rem === 1) {
    const b0 = bytes[full];
    parts.push(ALPHABET[(b0 >> 2) & 63], ALPHABET[(b0 << 4) & 63]);
  } else if (rem === 2) {
    const b0 = bytes[full];
    const b1 = bytes[full + 1];
    parts.push(
      ALPHABET[(b0 >> 2) & 63],
      ALPHABET[(((b0 & 3) << 4) | (b1 >> 4)) & 63],
      ALPHABET[(b1 << 2) & 63],
    );
  }
  return parts.join('');
}

export function decodeBase64Url(input: string): Uint8Array {
  const n = input.length;
  // RawURLEncoding never emits padding, so the input length modulo 4 decides
  // the tail; a length of 1 mod 4 can never decode.
  if (n % 4 === 1) throw new Error('invalid base64url: impossible length');
  const tail = n % 4;
  const body = n - tail;
  const out = new Uint8Array(
    (body >> 2) * 3 + (tail === 2 ? 1 : tail === 3 ? 2 : 0),
  );
  let op = 0;
  for (let i = 0; i < body; i += 4) {
    const v0 = index(input.charCodeAt(i));
    const v1 = index(input.charCodeAt(i + 1));
    const v2 = index(input.charCodeAt(i + 2));
    const v3 = index(input.charCodeAt(i + 3));
    if (v0 < 0 || v1 < 0 || v2 < 0 || v3 < 0) {
      throw new Error('invalid base64url: character out of alphabet');
    }
    const triple = (v0 << 18) | (v1 << 12) | (v2 << 6) | v3;
    out[op++] = (triple >> 16) & 0xff;
    out[op++] = (triple >> 8) & 0xff;
    out[op++] = triple & 0xff;
  }
  if (tail === 2) {
    const v0 = index(input.charCodeAt(body));
    const v1 = index(input.charCodeAt(body + 1));
    if (v0 < 0 || v1 < 0)
      throw new Error('invalid base64url: character out of alphabet');
    out[op] = (v0 << 2) | (v1 >> 4);
  } else if (tail === 3) {
    const v0 = index(input.charCodeAt(body));
    const v1 = index(input.charCodeAt(body + 1));
    const v2 = index(input.charCodeAt(body + 2));
    if (v0 < 0 || v1 < 0 || v2 < 0) {
      throw new Error('invalid base64url: character out of alphabet');
    }
    out[op++] = (v0 << 2) | (v1 >> 4);
    out[op] = ((v1 & 0x0f) << 4) | (v2 >> 2);
  }
  return out;
}
