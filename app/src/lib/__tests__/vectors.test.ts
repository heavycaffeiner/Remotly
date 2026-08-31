// v1 freeze: JS fixture tests against the canonical wire vectors.
//
// These read the same vector files the Go tests exercise
// (daemon/internal/protocol/testdata) and assert the JS-implemented byte
// formats reproduce them exactly. This is the app-side half of the byte-
// identical interop proof for the v1 freeze. The vectors use synthetic keys
// and secrets only.
import { describe, expect, it } from '@jest/globals';

import * as fs from 'fs';
import * as path from 'path';

import { decodeBase64Url, encodeBase64Url } from '../base64url';
import { parsePairingURI } from '../pairing';
import { decodeChunkFrame, encodeChunkFrame } from '../daemonTransfer';

// Vector directory, relative to this file (src/lib/__tests__).
const VECTORS = path.resolve(
  __dirname,
  '../../../..',
  'daemon/internal/protocol/testdata',
);

function loadVector(name: string): unknown {
  return JSON.parse(fs.readFileSync(path.join(VECTORS, name), 'utf8')) as never;
}

function fromHex(s: string): Uint8Array {
  const out = new Uint8Array(s.length / 2);
  for (let i = 0; i < out.length; i++) {
    out[i] = parseInt(s.slice(i * 2, i * 2 + 2), 16);
  }
  return out;
}

function toHex(b: Uint8Array): string {
  let s = '';
  for (const x of b) s += x.toString(16).padStart(2, '0');
  return s;
}

// Fixed clock earlier than every vector's expiry, so the pairing assertions
// are deterministic and independent of when the suite runs.
const NOW = 1787000000;

describe('v1 vectors: base64url', () => {
  const cases = loadVector('base64url.json') as {
    bytes: string;
    encoded: string;
  }[];
  it('encodes and decodes byte-identically to Go', () => {
    for (const c of cases) {
      const raw = fromHex(c.bytes);
      expect(encodeBase64Url(raw)).toBe(c.encoded);
      expect(toHex(decodeBase64Url(c.encoded))).toBe(c.bytes);
    }
  });
});

describe('v1 vectors: transfer chunk framing', () => {
  const cases = loadVector('transfer-chunk.json') as {
    offset: number;
    chunk: string;
    payload: string;
  }[];
  it('frames offset+chunk byte-identically to Go', () => {
    for (const c of cases) {
      const chunk = fromHex(c.chunk);
      const frame = encodeChunkFrame(c.offset, chunk);
      expect(toHex(frame)).toBe(c.payload);
      const decoded = decodeChunkFrame(frame);
      expect(decoded).not.toBeNull();
      expect(decoded!.offset).toBe(c.offset);
      expect(toHex(decoded!.payload)).toBe(c.chunk);
    }
  });
});

describe('v1 vectors: pairing URI payload', () => {
  const cases = loadVector('pairing-payload.json') as {
    token_id: string;
    secret: string;
    expires: number;
    ephemeral_pub: string;
    daemon_pub: string;
    hints: { kind: number; addr: string; port: number }[];
    daemon_name: string;
    uri: string;
  }[];

  it('parses the daemon-encoded URIs and recovers every field', () => {
    for (const c of cases) {
      const result = parsePairingURI(c.uri, NOW);
      if (c.hints.length === 0) {
        // The byte codec fully parsed the payload; the app layer then rejects
        // it for having no dialable target. That confirms the wire bytes
        // decode correctly on the JS side.
        expect(result.ok).toBe(false);
        if (!result.ok) expect(result.error).toBe('no-usable-hints');
        continue;
      }
      expect(result.ok).toBe(true);
      if (!result.ok) continue;
      const p = result.payload;
      expect(toHex(p.tokenID)).toBe(c.token_id);
      expect(toHex(p.secret)).toBe(c.secret);
      expect(p.expires).toBe(c.expires);
      expect(toHex(p.ephemeralPub)).toBe(c.ephemeral_pub);
      expect(toHex(p.daemonPub)).toBe(c.daemon_pub);
      expect(p.daemonName).toBe(c.daemon_name);
      expect(p.hints).toHaveLength(c.hints.length);
      for (let i = 0; i < c.hints.length; i++) {
        expect(p.hints[i].kind).toBe(c.hints[i].kind);
        expect(p.hints[i].addr).toBe(c.hints[i].addr);
        expect(p.hints[i].port).toBe(c.hints[i].port);
      }
    }
  });
});
