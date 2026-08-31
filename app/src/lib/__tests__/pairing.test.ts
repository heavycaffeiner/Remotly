import { describe, expect, it } from '@jest/globals';

import { encodeBase64Url } from '../base64url';
import {
  HINT_IPV4,
  HINT_IPV6,
  HINT_NAME,
  HINT_RELAY,
  PAIRING_URI_PREFIX,
  hintTarget,
  hintUsable,
  parsePairingURI,
  relayIdFromDaemonPub,
  type PairingHint,
} from '../pairing';

// --- payload construction ----------------------------------------------------
//
// buildRaw renders the daemon's wire layout by hand so the app parser is
// exercised against the same byte sequences the daemon codec produces
// (daemon/internal/pairing/uri.go and its tests).

function pattern(len: number, f: (i: number) => number): Uint8Array {
  const out = new Uint8Array(len);
  for (let i = 0; i < len; i++) out[i] = f(i) & 0xff;
  return out;
}

const HINT_COUNT_OFFSET = 1 + 16 + 32 + 4 + 32 + 32;

function utf8Bytes(s: string): number[] {
  const out: number[] = [];
  for (let i = 0; i < s.length; i++) out.push(s.charCodeAt(i) & 0xff);
  return out;
}

interface BuildOpts {
  version?: number;
  tokenID?: Uint8Array;
  secret?: Uint8Array;
  expires?: number;
  ephemeralPub?: Uint8Array;
  daemonPub?: Uint8Array;
  hints?: PairingHint[];
  daemonName?: string;
  hintCountOverride?: number;
  nameBytes?: number[];
}

function buildRaw(opts: BuildOpts = {}): number[] {
  const tokenID = opts.tokenID ?? pattern(16, i => i + 1);
  const secret = opts.secret ?? pattern(32, i => i + 101);
  const ephemeralPub = opts.ephemeralPub ?? pattern(32, i => i);
  const daemonPub = opts.daemonPub ?? pattern(32, i => 200 + (i % 56));
  const hints = opts.hints ?? [
    { kind: HINT_IPV4, addr: '192.168.1.10', port: 8443 },
    { kind: HINT_IPV6, addr: 'fe80::42', port: 8443 },
    { kind: HINT_NAME, addr: 'myhost', port: 9000 },
  ];
  const r: number[] = [];
  r.push(opts.version ?? 1);
  for (const b of tokenID) r.push(b);
  for (const b of secret) r.push(b);
  const exp = opts.expires ?? 1_893_456_789;
  r.push(
    (exp >>> 24) & 0xff,
    (exp >>> 16) & 0xff,
    (exp >>> 8) & 0xff,
    exp & 0xff,
  );
  for (const b of ephemeralPub) r.push(b);
  for (const b of daemonPub) r.push(b);
  r.push(opts.hintCountOverride ?? hints.length);
  for (const h of hints) {
    r.push(h.kind);
    const addr = utf8Bytes(h.addr);
    if (addr.length >= 128)
      throw new Error('test hint addresses must be < 128 bytes');
    r.push(addr.length);
    for (const b of addr) r.push(b);
    r.push((h.port >> 8) & 0xff, h.port & 0xff);
  }
  const name = opts.nameBytes ?? utf8Bytes(opts.daemonName ?? 'dev-1');
  r.push(name.length);
  for (const b of name) r.push(b);
  return r;
}

function uri(raw: number[]): string {
  return PAIRING_URI_PREFIX + encodeBase64Url(new Uint8Array(raw));
}

function ok(result: ReturnType<typeof parsePairingURI>) {
  if (!result.ok) throw new Error(`expected ok, got ${result.error}`);
  return result;
}

function err(result: ReturnType<typeof parsePairingURI>) {
  if (result.ok) throw new Error('expected error, got ok');
  return result.error;
}

// The far-future expiry of the default payload, pinned so tests do not drift
// into the expired branch.
const FUTURE = 1_893_456_789;
const NOW = 1_700_000_000;

// --- codec level (mirrors daemon TestURIDecodeMalformed) ----------------------

describe('parsePairingURI codec level', () => {
  it('round-trips the reference payload', () => {
    const result = ok(parsePairingURI(uri(buildRaw()), NOW));
    expect(result.payload.version).toBe(1);
    expect(result.payload.tokenID).toEqual(pattern(16, i => i + 1));
    expect(result.payload.secret).toEqual(pattern(32, i => i + 101));
    expect(result.payload.expires).toBe(FUTURE);
    expect(result.payload.ephemeralPub).toEqual(pattern(32, i => i));
    expect(result.payload.daemonPub).toEqual(pattern(32, i => 200 + (i % 56)));
    expect(result.payload.hints).toEqual([
      { kind: HINT_IPV4, addr: '192.168.1.10', port: 8443 },
      { kind: HINT_IPV6, addr: 'fe80::42', port: 8443 },
      { kind: HINT_NAME, addr: 'myhost', port: 9000 },
    ]);
    expect(result.payload.daemonName).toBe('dev-1');
  });

  it('parses a hint-less payload at codec level but rejects it at app level', () => {
    // Zero hints is a well-formed payload; the app-level rule is that a
    // pairing must carry at least one reachable address.
    expect(err(parsePairingURI(uri(buildRaw({ hints: [] })), NOW))).toBe(
      'no-usable-hints',
    );
  });

  it('rejects an empty payload before any decode', () => {
    expect(err(parsePairingURI(uri([]), NOW))).toBe('empty-payload');
  });

  const malformed: [string, number[]][] = [
    ['truncated version only', buildRaw().slice(0, 1)],
    ['truncated token id', buildRaw().slice(0, 5)],
    ['truncated secret', buildRaw().slice(0, 16 + 10)],
    ['truncated expires', buildRaw().slice(0, 16 + 32 + 2)],
    ['truncated ephemeral pub', buildRaw().slice(0, 16 + 32 + 4 + 16)],
    ['truncated daemon pub', buildRaw().slice(0, 16 + 32 + 4 + 32 + 10)],
    ['truncated hint count', buildRaw().slice(0, HINT_COUNT_OFFSET)],
  ];
  it.each(malformed)('rejects %s with truncated', (_name, raw) => {
    expect(err(parsePairingURI(uri(raw), NOW))).toBe('truncated');
  });

  it('rejects a bad version', () => {
    const raw = buildRaw();
    raw[0] = 2;
    expect(err(parsePairingURI(uri(raw), NOW))).toBe('bad-version');
  });

  it('rejects trailing bytes', () => {
    const raw = [...buildRaw(), 0];
    expect(err(parsePairingURI(uri(raw), NOW))).toBe('trailing-bytes');
  });

  it('rejects a bad hint kind', () => {
    // Kind 3 (relay) is valid in v1; anything above it is rejected.
    const raw = buildRaw();
    raw[HINT_COUNT_OFFSET + 1] = 4;
    expect(err(parsePairingURI(uri(raw), NOW))).toBe('bad-hint-kind');
  });

  it('rejects an empty hint address', () => {
    const raw = buildRaw();
    raw[HINT_COUNT_OFFSET + 2] = 0;
    expect(err(parsePairingURI(uri(raw), NOW))).toBe('hint-addr-range');
  });

  it('rejects a zero hint port', () => {
    const raw = buildRaw();
    raw[HINT_COUNT_OFFSET + 15] = 0;
    raw[HINT_COUNT_OFFSET + 16] = 0;
    expect(err(parsePairingURI(uri(raw), NOW))).toBe('hint-port-range');
  });

  it('rejects an empty daemon name', () => {
    const raw = buildRaw({ hints: [] });
    // Tail is name_len(1) + "dev-1"(5); zero the length byte.
    raw[raw.length - 6] = 0;
    expect(err(parsePairingURI(uri(raw), NOW))).toBe('name-range');
  });

  it('rejects an oversized daemon name', () => {
    const raw = buildRaw({ hints: [], daemonName: 'n'.repeat(101) });
    expect(err(parsePairingURI(uri(raw), NOW))).toBe('name-range');
  });

  it('rejects a daemon name with a control character', () => {
    const raw = buildRaw({ hints: [], daemonName: 'a\x01b' });
    expect(err(parsePairingURI(uri(raw), NOW))).toBe('bad-name');
  });

  it('rejects a daemon name that is not valid UTF-8', () => {
    const raw = buildRaw({ hints: [], nameBytes: [0x61, 0xff, 0x62] });
    expect(err(parsePairingURI(uri(raw), NOW))).toBe('bad-name');
  });

  it('rejects a daemon name that is only a DEL character', () => {
    const raw = buildRaw({ hints: [], daemonName: '\x7f' });
    expect(err(parsePairingURI(uri(raw), NOW))).toBe('bad-name');
  });

  it('rejects a hint count above the maximum', () => {
    const raw = buildRaw({ hints: [], hintCountOverride: 9, daemonName: 'a' });
    expect(err(parsePairingURI(uri(raw), NOW))).toBe('too-many-hints');
  });

  it('rejects a varint longer than five bytes', () => {
    const noHints = buildRaw({ hints: [] });
    const raw = [
      ...noHints.slice(0, HINT_COUNT_OFFSET + 1),
      0xff,
      0xff,
      0xff,
      0xff,
      0xff,
    ];
    expect(err(parsePairingURI(uri(raw), NOW))).toBe('truncated');
  });

  it('rejects a payload larger than the bound', () => {
    const raw = new Array(4097).fill(0);
    expect(err(parsePairingURI(uri(raw), NOW))).toBe('too-large');
  });

  it('rejects an oversized encoded payload before decoding', () => {
    const result = parsePairingURI(PAIRING_URI_PREFIX + 'A'.repeat(6000), NOW);
    expect(err(result)).toBe('too-large');
  });

  it('rejects a non-base64 payload', () => {
    expect(
      err(parsePairingURI(PAIRING_URI_PREFIX + '!!!not-base64!!!', NOW)),
    ).toBe('bad-encoding');
  });

  it('rejects a wrong scheme', () => {
    expect(err(parsePairingURI('https://example.com/pair?d=abc', NOW))).toBe(
      'not-a-pairing-uri',
    );
  });

  it('rejects an empty d parameter', () => {
    expect(err(parsePairingURI('remotly://pair?d=', NOW))).toBe(
      'empty-payload',
    );
  });

  it('rejects standard-alphabet base64', () => {
    const raw = buildRaw();
    const b64 = Buffer.from(new Uint8Array(raw)).toString('base64');
    expect(err(parsePairingURI(PAIRING_URI_PREFIX + b64, NOW))).toBe(
      'bad-encoding',
    );
  });

  it('rejects padded base64', () => {
    const raw = buildRaw();
    const b64 = Buffer.from(new Uint8Array(raw))
      .toString('base64')
      .replace(/[=]+$/, '');
    expect(err(parsePairingURI(PAIRING_URI_PREFIX + b64 + '=', NOW))).toBe(
      'bad-encoding',
    );
  });
});

// --- app level (expiry, zero keys, address class) ------------------------------

describe('parsePairingURI app level', () => {
  it('rejects an expired payload', () => {
    expect(err(parsePairingURI(uri(buildRaw({ expires: NOW })), NOW))).toBe(
      'expired',
    );
    expect(err(parsePairingURI(uri(buildRaw({ expires: NOW - 1 })), NOW))).toBe(
      'expired',
    );
  });

  it('accepts a payload that expires in the future', () => {
    const result = ok(
      parsePairingURI(uri(buildRaw({ expires: NOW + 60 })), NOW),
    );
    expect(result.payload.expires).toBe(NOW + 60);
  });

  it('rejects an all-zero secret', () => {
    expect(
      err(parsePairingURI(uri(buildRaw({ secret: new Uint8Array(32) })), NOW)),
    ).toBe('all-zero-key');
  });

  it('rejects an all-zero daemon public key', () => {
    expect(
      err(
        parsePairingURI(uri(buildRaw({ daemonPub: new Uint8Array(32) })), NOW),
      ),
    ).toBe('all-zero-key');
  });

  it('rejects an all-zero ephemeral public key', () => {
    expect(
      err(
        parsePairingURI(
          uri(buildRaw({ ephemeralPub: new Uint8Array(32) })),
          NOW,
        ),
      ),
    ).toBe('all-zero-key');
  });

  it('rejects a payload with no hints', () => {
    expect(err(parsePairingURI(uri(buildRaw({ hints: [] })), NOW))).toBe(
      'no-usable-hints',
    );
  });

  it.each([
    ['loopback ipv4', '127.0.0.1'],
    ['this-network ipv4', '0.10.20.30'],
    ['multicast ipv4', '224.0.0.1'],
    ['broadcast ipv4', '255.255.255.255'],
    ['reserved ipv4', '240.0.0.1'],
    ['unspecified ipv6', '::'],
    ['loopback ipv6', '::1'],
    ['link-local ipv6', 'fe80::1'],
    ['multicast ipv6', 'ff02::1'],
  ])('rejects a %s-only payload', (_label, addr) => {
    const kind = addr.includes(':') ? HINT_IPV6 : HINT_IPV4;
    expect(
      err(
        parsePairingURI(
          uri(buildRaw({ hints: [{ kind, addr, port: 8788 }] })),
          NOW,
        ),
      ),
    ).toBe('no-usable-hints');
  });

  it('drops unusable hints but keeps the payload', () => {
    const hints: PairingHint[] = [
      { kind: HINT_IPV4, addr: '127.0.0.1', port: 1 },
      { kind: HINT_IPV4, addr: '192.168.1.10', port: 8443 },
      { kind: HINT_IPV6, addr: 'fe80::42', port: 8443 },
      { kind: HINT_NAME, addr: 'myhost', port: 9000 },
    ];
    const result = ok(parsePairingURI(uri(buildRaw({ hints })), NOW));
    expect(result.usableHints).toEqual([
      { kind: HINT_IPV4, addr: '192.168.1.10', port: 8443 },
      { kind: HINT_NAME, addr: 'myhost', port: 9000 },
    ]);
    expect(result.targets).toEqual(['192.168.1.10:8443', 'myhost:9000']);
  });

  it('formats ipv6 targets with brackets', () => {
    const hints: PairingHint[] = [
      { kind: HINT_IPV6, addr: 'fd00::1', port: 8788 },
    ];
    const result = ok(parsePairingURI(uri(buildRaw({ hints })), NOW));
    expect(result.targets).toEqual(['[fd00::1]:8788']);
  });

  it('keeps a name hint that is a valid hostname', () => {
    const hints: PairingHint[] = [
      { kind: HINT_NAME, addr: 'dev-box', port: 8788 },
    ];
    const result = ok(parsePairingURI(uri(buildRaw({ hints })), NOW));
    expect(result.targets).toEqual(['dev-box:8788']);
  });

  it('rejects a name hint containing a control character', () => {
    const hints: PairingHint[] = [
      { kind: HINT_NAME, addr: 'dev\x01box', port: 8788 },
    ];
    expect(err(parsePairingURI(uri(buildRaw({ hints })), NOW))).toBe(
      'no-usable-hints',
    );
  });

  it('rejects a name hint that is not valid UTF-8', () => {
    // buildRaw renders hint addresses as ASCII, so craft the bytes by hand.
    // Hint layout from HINT_COUNT_OFFSET+1: kind, addr_len, addr, port(2).
    const raw = buildRaw({
      hints: [{ kind: HINT_NAME, addr: 'x', port: 1 }],
      daemonName: 'd',
    });
    raw.splice(HINT_COUNT_OFFSET + 2, 2, 3, 0xff, 0xfe, 0x61);
    expect(err(parsePairingURI(uri(raw), NOW))).toBe('no-usable-hints');
  });

  it('accepts an ipv4-mapped-style tail in an ipv6 address', () => {
    const hints: PairingHint[] = [
      { kind: HINT_IPV6, addr: '64:ff9b::192.0.2.33', port: 1 },
    ];
    const result = ok(parsePairingURI(uri(buildRaw({ hints })), NOW));
    expect(result.targets).toEqual(['[64:ff9b::192.0.2.33]:1']);
  });

  it('parses a relay hint alongside direct hints', () => {
    const hints: PairingHint[] = [
      { kind: HINT_IPV4, addr: '192.168.1.10', port: 8443 },
      { kind: HINT_RELAY, addr: 'relay.example', port: 10000 },
    ];
    const result = ok(parsePairingURI(uri(buildRaw({ hints })), NOW));
    expect(result.targets).toEqual(['192.168.1.10:8443']);
    expect(result.usableHints).toEqual([
      { kind: HINT_IPV4, addr: '192.168.1.10', port: 8443 },
    ]);
    expect(result.relay).toEqual({
      host: 'relay.example',
      port: 10000,
      target: 'relay.example:10000',
    });
  });

  it('accepts a relay-only payload with no direct hints', () => {
    const hints: PairingHint[] = [
      { kind: HINT_RELAY, addr: 'relay.example', port: 10000 },
    ];
    const result = ok(parsePairingURI(uri(buildRaw({ hints })), NOW));
    expect(result.targets).toEqual([]);
    expect(result.usableHints).toEqual([]);
    expect(result.relay?.target).toBe('relay.example:10000');
  });

  it('rejects a relay hint with a loopback address', () => {
    const hints: PairingHint[] = [
      { kind: HINT_RELAY, addr: '127.0.0.1', port: 10000 },
    ];
    expect(err(parsePairingURI(uri(buildRaw({ hints })), NOW))).toBe(
      'no-usable-hints',
    );
  });

  it('derives the relay id from the daemon public key', () => {
    const daemonPub = pattern(32, i => 200 + (i % 56));
    const b64 = encodeBase64Url(daemonPub);
    const id = relayIdFromDaemonPub(b64);
    expect(id).toBe(encodeBase64Url(daemonPub.slice(0, 16)));
  });

  it('returns a null relay id for a malformed daemon key', () => {
    expect(relayIdFromDaemonPub('!!!')).toBeNull();
    expect(
      relayIdFromDaemonPub(encodeBase64Url(new Uint8Array(16))),
    ).toBeNull();
  });

  it('exposes hint usability and target formatting directly', () => {
    expect(hintUsable({ kind: HINT_IPV4, addr: '10.0.0.1', port: 1 })).toBe(
      true,
    );
    expect(hintUsable({ kind: HINT_IPV4, addr: '127.0.0.1', port: 1 })).toBe(
      false,
    );
    expect(hintUsable({ kind: HINT_IPV4, addr: '300.0.0.1', port: 1 })).toBe(
      false,
    );
    expect(hintUsable({ kind: HINT_IPV4, addr: '01.2.3.4', port: 1 })).toBe(
      false,
    );
    expect(hintUsable({ kind: HINT_IPV6, addr: '2001:db8::1', port: 1 })).toBe(
      true,
    );
    expect(hintUsable({ kind: HINT_IPV6, addr: 'fe80::1', port: 1 })).toBe(
      false,
    );
    expect(hintUsable({ kind: HINT_IPV6, addr: '2001::1%eth0', port: 1 })).toBe(
      false,
    );
    expect(hintTarget({ kind: HINT_IPV6, addr: '2001:db8::1', port: 80 })).toBe(
      '[2001:db8::1]:80',
    );
    expect(hintTarget({ kind: HINT_NAME, addr: 'host', port: 80 })).toBe(
      'host:80',
    );
  });
});
