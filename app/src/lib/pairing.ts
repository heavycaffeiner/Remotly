// Parser and validator for canonical Remotly pairing URIs.
//
// The wire format is defined by the daemon codec (daemon/internal/pairing/
// uri.go); this module mirrors it byte for byte so the app and daemon reject
// the same hostile inputs. On top of the codec it applies app-level usability
// validation: expiry, address class, and zero-key rejection. A pairing payload
// that parses but cannot reach the daemon is rejected here, before any
// connection is attempted.
//
// The secret is carried in the result for the handshake only. Screens must
// never render it; the host store must never persist it.
import { decodeBase64Url, encodeBase64Url } from './base64url';

export const PAIRING_URI_PREFIX = 'remotly://pair?d=';

// Payload bounds, identical to the daemon codec.
const URI_VERSION = 1;
const MAX_HINTS = 8;
const MAX_HINT_ADDR = 255;
const MAX_DAEMON_NAME = 100;
const MAX_URI_PAYLOAD = 4096;
// Longest base64url encoding of a MAX_URI_PAYLOAD byte string, checked before
// decoding so an oversized payload is rejected before it is allocated.
const MAX_ENCODED = Math.ceil(MAX_URI_PAYLOAD / 3) * 4;

// Hint kinds, identical to the daemon.
export const HINT_IPV4 = 0;
export const HINT_IPV6 = 1;
export const HINT_NAME = 2;
export const HINT_RELAY = 3;

export interface PairingHint {
  kind: 0 | 1 | 2 | 3;
  addr: string;
  port: number;
}

/** The relay target a pairing URI points at, when the daemon has a relay. */
export interface PairingRelay {
  host: string;
  port: number;
  /** Dialable "host:port" target for the relay. */
  target: string;
}

export interface PairingPayload {
  version: 1;
  tokenID: Uint8Array;
  secret: Uint8Array;
  /** Unix seconds after which the pairing token is invalid. */
  expires: number;
  ephemeralPub: Uint8Array;
  daemonPub: Uint8Array;
  hints: PairingHint[];
  daemonName: string;
}

export type PairingParseError =
  | 'not-a-pairing-uri'
  | 'empty-payload'
  | 'bad-encoding'
  | 'too-large'
  | 'truncated'
  | 'bad-version'
  | 'too-many-hints'
  | 'bad-hint-kind'
  | 'hint-addr-range'
  | 'hint-port-range'
  | 'name-range'
  | 'bad-name'
  | 'trailing-bytes'
  | 'expired'
  | 'all-zero-key'
  | 'no-usable-hints';

export type PairingResult =
  | {
      ok: true;
      payload: PairingPayload;
      /** Usable direct (LAN) hints, in payload order. */
      usableHints: PairingHint[];
      /** Dialable "host:port" targets for usableHints, in the same order. */
      targets: string[];
      /** The relay target, when the daemon paired with a relay enabled. */
      relay: PairingRelay | null;
    }
  | { ok: false; error: PairingParseError };

type CodecResult =
  | { ok: true; payload: PairingPayload }
  | {
      ok: false;
      error: Exclude<
        PairingParseError,
        'expired' | 'all-zero-key' | 'no-usable-hints'
      >;
    };

// Bounded cursor over the payload, mirroring the daemon's payloadReader.
// Reads return null on truncation; the caller decides the error code.
class Reader {
  private i = 0;

  constructor(private readonly b: Uint8Array) {}

  get remaining(): number {
    return this.b.length - this.i;
  }

  u8(): number | null {
    if (this.i + 1 > this.b.length) return null;
    const v = this.b[this.i];
    this.i++;
    return v;
  }

  bytes(n: number): Uint8Array | null {
    if (n < 0 || this.i + n > this.b.length) return null;
    const out = this.b.subarray(this.i, this.i + n);
    this.i += n;
    return out;
  }

  varint(): number | null {
    let v = 0;
    for (let i = 0; i < 5; i++) {
      if (this.i >= this.b.length) return null;
      const c = this.b[this.i];
      this.i++;
      v |= (c & 0x7f) * 2 ** (7 * i);
      if ((c & 0x80) === 0) return v;
    }
    return null;
  }
}

// Strict UTF-8 decode. Returns null on any invalid sequence (overlong,
// surrogate, out of range, truncated). Hermes has no TextDecoder, and a daemon
// name that is not valid UTF-8 is hostile input, not a display quirk.
function decodeUtf8(bytes: Uint8Array): string | null {
  let out = '';
  let i = 0;
  const n = bytes.length;
  while (i < n) {
    const b0 = bytes[i];
    let cp: number;
    let extra: number;
    if (b0 < 0x80) {
      cp = b0;
      extra = 0;
    } else if (b0 >= 0xc2 && b0 <= 0xdf) {
      cp = b0 & 0x1f;
      extra = 1;
    } else if (b0 >= 0xe0 && b0 <= 0xef) {
      cp = b0 & 0x0f;
      extra = 2;
    } else if (b0 >= 0xf0 && b0 <= 0xf4) {
      cp = b0 & 0x07;
      extra = 3;
    } else {
      return null;
    }
    let valid = true;
    for (let j = 1; j <= extra && valid; j++) {
      const b = bytes[i + j];
      if (b === undefined || (b & 0xc0) !== 0x80) {
        valid = false;
        break;
      }
      cp = (cp << 6) | (b & 0x3f);
    }
    if (!valid) return null;
    if (extra === 1 && cp < 0x80) return null;
    if (extra === 2 && (cp < 0x800 || (cp >= 0xd800 && cp <= 0xdfff)))
      return null;
    if (extra === 3 && (cp < 0x10000 || cp > 0x10ffff)) return null;
    out += String.fromCodePoint(cp);
    i += 1 + extra;
  }
  return out;
}

// Lenient UTF-8 decode for hint addresses: invalid bytes become U+FFFD. The
// codec does not require hint addresses to be UTF-8, and usability checks
// (IP parse, name rules) reject the replacement character anyway.
function decodeUtf8Lenient(bytes: Uint8Array): string {
  let out = '';
  let i = 0;
  while (i < bytes.length) {
    const b0 = bytes[i];
    if (b0 < 0x80) {
      out += String.fromCharCode(b0);
      i++;
      continue;
    }
    let extra = 0;
    if (b0 >= 0xc2 && b0 <= 0xdf) extra = 1;
    else if (b0 >= 0xe0 && b0 <= 0xef) extra = 2;
    else if (b0 >= 0xf0 && b0 <= 0xf4) extra = 3;
    let ok = true;
    for (let j = 1; j <= extra && ok; j++) {
      const b = bytes[i + j];
      if (b === undefined || (b & 0xc0) !== 0x80) ok = false;
    }
    if (!ok) {
      out += '\u{FFFD}';
      i++;
    } else {
      out += decodeUtf8(bytes.subarray(i, i + 1 + extra)) ?? '\u{FFFD}';
      i += 1 + extra;
    }
  }
  return out;
}

function bigEndian32(b: Uint8Array): number {
  return ((b[0] << 24) | (b[1] << 16) | (b[2] << 8) | b[3]) >>> 0;
}

// Codec-level parse, a byte-for-byte mirror of the daemon's parsePayload.
function parsePayload(raw: Uint8Array): CodecResult {
  const r = new Reader(raw);

  const version = r.u8();
  if (version === null) return { ok: false, error: 'truncated' };
  if (version !== URI_VERSION) return { ok: false, error: 'bad-version' };

  const tokenID = r.bytes(16);
  if (!tokenID) return { ok: false, error: 'truncated' };
  const secret = r.bytes(32);
  if (!secret) return { ok: false, error: 'truncated' };
  const expB = r.bytes(4);
  if (!expB) return { ok: false, error: 'truncated' };
  const ephemeralPub = r.bytes(32);
  if (!ephemeralPub) return { ok: false, error: 'truncated' };
  const daemonPub = r.bytes(32);
  if (!daemonPub) return { ok: false, error: 'truncated' };

  const hintCount = r.u8();
  if (hintCount === null) return { ok: false, error: 'truncated' };
  if (hintCount > MAX_HINTS) return { ok: false, error: 'too-many-hints' };

  const hints: PairingHint[] = [];
  for (let i = 0; i < hintCount; i++) {
    const kind = r.u8();
    if (kind === null) return { ok: false, error: 'truncated' };
    if (kind > HINT_RELAY) return { ok: false, error: 'bad-hint-kind' };
    const addrLen = r.varint();
    if (addrLen === null) return { ok: false, error: 'truncated' };
    if (addrLen < 1 || addrLen > MAX_HINT_ADDR) {
      return { ok: false, error: 'hint-addr-range' };
    }
    const addrBytes = r.bytes(addrLen);
    if (!addrBytes) return { ok: false, error: 'truncated' };
    const portB = r.bytes(2);
    if (!portB) return { ok: false, error: 'truncated' };
    const port = (portB[0] << 8) | portB[1];
    if (port < 1 || port > 65535)
      return { ok: false, error: 'hint-port-range' };
    hints.push({
      kind: kind as 0 | 1 | 2 | 3,
      addr: decodeUtf8Lenient(addrBytes),
      port,
    });
  }

  const nameLen = r.varint();
  if (nameLen === null) return { ok: false, error: 'truncated' };
  if (nameLen < 1 || nameLen > MAX_DAEMON_NAME)
    return { ok: false, error: 'name-range' };
  const nameBytes = r.bytes(nameLen);
  if (!nameBytes) return { ok: false, error: 'truncated' };
  const daemonName = decodeUtf8(nameBytes);
  if (daemonName === null) return { ok: false, error: 'bad-name' };
  for (let i = 0; i < daemonName.length; i++) {
    const code = daemonName.charCodeAt(i);
    if (code < 0x20 || code === 0x7f) return { ok: false, error: 'bad-name' };
  }

  if (r.remaining !== 0) return { ok: false, error: 'trailing-bytes' };

  return {
    ok: true,
    payload: {
      version: URI_VERSION,
      tokenID,
      secret,
      expires: bigEndian32(expB),
      ephemeralPub,
      daemonPub,
      hints,
      daemonName,
    },
  };
}

// --- Address class checks ----------------------------------------------------

function parseIPv4(s: string): number | null {
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(s);
  if (!m) return null;
  let v = 0;
  for (let i = 1; i <= 4; i++) {
    const part = m[i];
    // Leading zeros are rejected like Go's net.ParseIP does.
    if (part.length > 1 && part[0] === '0') return null;
    const n = Number(part);
    if (n > 255) return null;
    v = (v << 8) | n;
  }
  return v;
}

// Expands an IPv6 address to eight 16-bit groups, or null when the string is
// not a plain (zone-less) IPv6 address. Supports "::" compression and an
// embedded IPv4 tail.
function parseIPv6(s: string): number[] | null {
  if (s.length < 2 || s.length > 45 || s.includes('%')) return null;
  let addr = s;
  let v4: number[] | null = null;
  const lastDot = s.lastIndexOf('.');
  if (lastDot >= 0) {
    const lastColon = s.lastIndexOf(':');
    if (lastColon < 0 || lastDot < lastColon) return null;
    const p = parseIPv4(s.slice(lastColon + 1));
    if (p === null) return null;
    v4 = [(p >>> 8) & 0xffff, p & 0xffff];
    addr = s.slice(0, lastColon + 1) + 'x';
  }
  const doubleIdx = addr.indexOf('::');
  if (doubleIdx >= 0 && addr.indexOf('::', doubleIdx + 1) >= 0) return null;
  const head = doubleIdx >= 0 ? addr.slice(0, doubleIdx) : addr;
  const tail = doubleIdx >= 0 ? addr.slice(doubleIdx + 2) : '';
  const headParts = head === '' ? [] : head.split(':');
  const tailParts = tail === '' ? [] : tail.split(':');
  const parsePart = (p: string): number | null => {
    if (p === 'x') return -1;
    if (!/^[0-9a-fA-F]{1,4}$/.test(p)) return null;
    return parseInt(p, 16);
  };
  const groups: number[] = [];
  for (const p of headParts) {
    const g = parsePart(p);
    if (g === null) return null;
    if (g === -1) {
      if (v4 === null) return null;
      groups.push(v4[0], v4[1]);
    } else {
      groups.push(g);
    }
  }
  for (const p of tailParts) {
    const g = parsePart(p);
    if (g === null) return null;
    if (g === -1) {
      if (v4 === null) return null;
      groups.push(v4[0], v4[1]);
    } else {
      groups.push(g);
    }
  }
  if (doubleIdx < 0) {
    return groups.length === 8 ? groups : null;
  }
  const gap = 8 - groups.length;
  if (gap < 1) return null;
  const headCount = headParts.reduce((acc, p) => acc + (p === 'x' ? 2 : 1), 0);
  const out = groups.slice(0, headCount);
  for (let i = 0; i < gap; i++) out.push(0);
  out.push(...groups.slice(headCount));
  return out;
}

function ipv4Hostile(v: number): boolean {
  const a = (v >>> 24) & 0xff;
  if (a === 0) return true; // 0.0.0.0/8: unspecified and "this network"
  if (a === 127) return true; // loopback
  if (a >= 224) return true; // multicast 224/4 plus reserved 240/4
  if (a === 255) return true; // broadcast and reserved
  return false;
}

function ipv6Hostile(g: number[]): boolean {
  if (g.every(x => x === 0)) return true; // ::
  if (
    g[0] === 0 &&
    g[1] === 0 &&
    g[2] === 0 &&
    g[3] === 0 &&
    g[4] === 0 &&
    g[5] === 0 &&
    g[6] === 0 &&
    g[7] === 1
  ) {
    return true; // ::1
  }
  const first = g[0];
  if ((first & 0xffc0) === 0xfe80) return true; // link-local fe80::/10
  if ((first & 0xff00) === 0xff00) return true; // multicast ff00::/8
  return false;
}

function nameUsable(addr: string): boolean {
  if (addr.length < 1 || addr.length > MAX_HINT_ADDR) return false;
  for (let i = 0; i < addr.length; i++) {
    const c = addr.charCodeAt(i);
    if (c === 0xfffd || c < 0x20 || c === 0x7f) return false;
  }
  return true;
}

// A hint is usable when the app could actually dial it. Loopback, multicast,
// broadcast, link-local, and unspecified addresses point at the wrong end of
// the wire, so they are dropped rather than dialed.
export function hintUsable(h: PairingHint): boolean {
  if (h.kind === HINT_IPV4) {
    const v = parseIPv4(h.addr);
    return v !== null && !ipv4Hostile(v);
  }
  if (h.kind === HINT_IPV6) {
    const g = parseIPv6(h.addr);
    return g !== null && !ipv6Hostile(g);
  }
  // A relay hint is not a direct target; it is surfaced separately as the
  // relay fallback, never dialed as a LAN address.
  if (h.kind === HINT_RELAY) return false;
  return nameUsable(h.addr);
}

// A relay hint names the relay's public address (dialled by the app, not the
// daemon), so the kind-based hintUsable short-circuit does not apply. The
// address must still be a dialable remote host: loopback, link-local,
// multicast, broadcast, and unspecified addresses point at the wrong end of
// the wire and are dropped, leaving the direct hints to carry the pairing.
function relayHintUsable(h: PairingHint): boolean {
  if (h.addr.includes(':')) {
    const g = parseIPv6(h.addr);
    return g !== null && !ipv6Hostile(g);
  }
  const v = parseIPv4(h.addr);
  if (v !== null) return !ipv4Hostile(v);
  return nameUsable(h.addr);
}

// Dialable target in the transport's "host" / "host:port" / "[ipv6]:port"
// form (see TransportHub.parseTarget). Takes a structural hint shape so both
// pairing hints and stored host hints work.
export function hintTarget(h: {
  kind: number;
  addr: string;
  port: number;
}): string {
  return h.kind === HINT_IPV6 ? `[${h.addr}]:${h.port}` : `${h.addr}:${h.port}`;
}

function isZero(b: Uint8Array): boolean {
  for (let i = 0; i < b.length; i++) if (b[i] !== 0) return false;
  return true;
}

/**
 * The 16-byte relay id is the first 16 bytes of the daemon's 32-byte public
 * key. Returns the unpadded base64url of those bytes, or null when the public
 * key is not a valid 32-byte value. The relay stores the id opaquely; the app
 * always derives it from the pinned key, so it is never stored separately.
 */
export function relayIdFromDaemonPub(daemonPubB64: string): string | null {
  let pub: Uint8Array;
  try {
    pub = decodeBase64Url(daemonPubB64);
  } catch {
    return null;
  }
  if (pub.length !== 32) return null;
  return encodeBase64Url(pub.slice(0, 16));
}

// Parses and validates a pairing URI. `nowSeconds` defaults to the current
// time; tests pin it.
export function parsePairingURI(
  uri: string,
  nowSeconds?: number,
): PairingResult {
  if (!uri.startsWith(PAIRING_URI_PREFIX)) {
    return { ok: false, error: 'not-a-pairing-uri' };
  }
  const encoded = uri.slice(PAIRING_URI_PREFIX.length);
  if (encoded.length === 0) return { ok: false, error: 'empty-payload' };
  if (encoded.length > MAX_ENCODED) return { ok: false, error: 'too-large' };
  let raw: Uint8Array;
  try {
    raw = decodeBase64Url(encoded);
  } catch {
    return { ok: false, error: 'bad-encoding' };
  }
  if (raw.length > MAX_URI_PAYLOAD) return { ok: false, error: 'too-large' };

  const parsed = parsePayload(raw);
  if (!parsed.ok) return parsed;
  const payload = parsed.payload;

  const now = nowSeconds ?? Math.floor(Date.now() / 1000);
  if (payload.expires <= now) return { ok: false, error: 'expired' };
  if (
    isZero(payload.secret) ||
    isZero(payload.daemonPub) ||
    isZero(payload.ephemeralPub)
  ) {
    return { ok: false, error: 'all-zero-key' };
  }
  const relayHint = payload.hints.find(
    h => h.kind === HINT_RELAY && relayHintUsable(h),
  );
  const relay: PairingRelay | null = relayHint
    ? {
        host: relayHint.addr,
        port: relayHint.port,
        target: hintTarget(relayHint),
      }
    : null;
  const usableHints = payload.hints.filter(
    h => h.kind !== HINT_RELAY && hintUsable(h),
  );
  // A pairing is usable when it has a dialable direct hint or a relay target.
  if (usableHints.length === 0 && relay === null) {
    return { ok: false, error: 'no-usable-hints' };
  }
  return {
    ok: true,
    payload,
    usableHints,
    targets: usableHints.map(hintTarget),
    relay,
  };
}

// Stable user-facing copy per parse error. Rendered as-is by the pairing
// screen; no raw payload content is interpolated in.
export const PAIRING_ERROR_TEXT: Record<PairingParseError, string> = {
  'not-a-pairing-uri': 'Not a Remotly pairing link.',
  'empty-payload': 'The pairing link is empty.',
  'bad-encoding': 'The pairing link is corrupted.',
  'too-large': 'The pairing link is too large.',
  truncated: 'The pairing link is incomplete.',
  'bad-version': 'Unsupported pairing version.',
  'too-many-hints': 'The pairing link has too many addresses.',
  'bad-hint-kind': 'The pairing link has an unknown address type.',
  'hint-addr-range': 'The pairing link has an invalid address.',
  'hint-port-range': 'The pairing link has an invalid port.',
  'name-range': 'The pairing link has an invalid device name.',
  'bad-name': 'The pairing link has an invalid device name.',
  'trailing-bytes': 'The pairing link is corrupted.',
  expired: 'This pairing code has expired. Start a new pairing on the device.',
  'all-zero-key': 'The pairing link is not usable.',
  'no-usable-hints': 'The pairing link has no reachable address.',
};
