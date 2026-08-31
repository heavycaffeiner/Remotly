// App-side client for the persistent daemon host store.
//
// The store itself is native (com.remotly.app.hosts.HostStore): an atomic,
// quarantining JSON file that pins each host to its daemon public key. This
// module is the only JS surface: it calls the IDL bridge methods, parses the
// JSON the native side carries, and maps native failures into RemotlyError so
// screens never see raw bridge codes.
import NativeHosts from '../specs/NativeRemotlyHosts';
import NativePairing from '../specs/NativeRemotlyPairing';
import { makeRemotlyError } from './errors';
import type { RemotlyErrorKind } from './errors';

/** One persisted daemon host, as stored natively. */
export interface HostRecord {
  /** Unpadded base64url of the 32-byte daemon public key. Stable identity. */
  id: string;
  daemonName: string;
  /** Unpadded base64url of the pinned 32-byte daemon public key. */
  daemonPub: string;
  hints: HostHint[];
  /** Unix seconds. */
  pairedAt: number;
  /** Unix seconds. */
  lastConnectedAt: number;
}

/** A validated LAN hint. kind: 0 = IPv4, 1 = IPv6, 2 = name. */
export interface HostHint {
  kind: number;
  addr: string;
  port: number;
}

export interface AddHostOptions {
  daemonName: string;
  /** Unpadded base64url of the 32-byte daemon public key. */
  daemonPub: string;
  hints: HostHint[];
}

export interface AddHostResult {
  id: string;
  /** True when an existing host with the same key was refreshed. */
  duplicate: boolean;
}

export interface RemotlyHosts {
  /** All persisted hosts, or an empty list when none exist. */
  list(): Promise<HostRecord[]>;
  /** Add or refresh a host. Never replaces a pinned key for a changed id. */
  add(options: AddHostOptions): Promise<AddHostResult>;
  /** Remove a host record. Resolves even when the id is unknown. */
  remove(id: string): Promise<void>;
  /** Mark a host as just connected. Resolves even when the id is unknown. */
  touch(id: string): Promise<void>;
  /**
   * Drain the one-shot pairing URI left by a deep link, if any. Returns ''
   * when there is none. Each call consumes the value.
   */
  takePendingPairingURI(): Promise<string>;
}

const INVALID_PARAMS = 'hosts-invalid-params';

// A bridge failure here is either a bad parameter (kept as unknown so the UI
// does not blame storage) or a store failure (the storage kind, whose copy
// says the data was not changed).
function bridgeError(code: number, msg: string) {
  const kind: RemotlyErrorKind = code === -3 ? 'unknown' : 'storage';
  const finalCode = code === -3 ? INVALID_PARAMS : code;
  return makeRemotlyError(kind, finalCode, new Error(msg));
}

function invalidParams(message: string) {
  return makeRemotlyError('unknown', INVALID_PARAMS, new Error(message));
}

// Adapts a TurboModule rejection (code is the numeric bridge code as a string)
// to a branded RemotlyError.
function fromRejection(e: unknown) {
  const code = Number((e as { code?: string })?.code ?? 0);
  const msg = (e as Error)?.message ?? 'bridge call failed';
  return bridgeError(code, msg);
}

function toVoid(p: Promise<unknown>): Promise<void> {
  return p.then(
    () => undefined,
    e => {
      throw fromRejection(e);
    },
  );
}

// The native side serializes records with Gson; this validates the shape at
// the boundary so a bridge or store regression surfaces as an error, not a
// crash in the UI.
function parseHosts(json: string): HostRecord[] {
  let value: unknown;
  try {
    value = JSON.parse(json);
  } catch {
    throw invalidParams('hosts payload is not valid JSON');
  }
  if (!Array.isArray(value)) {
    throw invalidParams('hosts payload is not an array');
  }
  return value.map((el, i) => {
    const o = el as Record<string, unknown>;
    const hints = o.hints;
    if (
      typeof o.id !== 'string' ||
      typeof o.daemonName !== 'string' ||
      typeof o.daemonPub !== 'string' ||
      !Array.isArray(hints) ||
      typeof o.pairedAt !== 'number' ||
      typeof o.lastConnectedAt !== 'number'
    ) {
      throw invalidParams(`host record ${i} is malformed`);
    }
    const parsedHints = hints.map((h, j) => {
      const ho = h as Record<string, unknown>;
      if (
        typeof ho.kind !== 'number' ||
        typeof ho.addr !== 'string' ||
        typeof ho.port !== 'number'
      ) {
        throw invalidParams(`host record ${i} hint ${j} is malformed`);
      }
      return { kind: ho.kind, addr: ho.addr, port: ho.port };
    });
    return {
      id: o.id,
      daemonName: o.daemonName,
      daemonPub: o.daemonPub,
      hints: parsedHints,
      pairedAt: o.pairedAt,
      lastConnectedAt: o.lastConnectedAt,
    };
  });
}

const hosts: RemotlyHosts = {
  list() {
    return NativeHosts.list().then(
      data => parseHosts(data.hosts),
      e => {
        throw fromRejection(e);
      },
    );
  },

  add(options) {
    const name = options.daemonName.trim();
    if (name === '') {
      return Promise.reject(invalidParams('daemonName must be non-empty'));
    }
    if (
      typeof options.daemonPub !== 'string' ||
      options.daemonPub.length !== 43
    ) {
      return Promise.reject(
        invalidParams('daemonPub must be 43 unpadded base64url chars'),
      );
    }
    if (!Array.isArray(options.hints)) {
      return Promise.reject(invalidParams('hints must be an array'));
    }
    return NativeHosts.add(
      name,
      options.daemonPub,
      JSON.stringify(options.hints),
    ).catch(e => {
      throw fromRejection(e);
    });
  },

  remove(id) {
    if (typeof id !== 'string' || id.length === 0) {
      return Promise.reject(invalidParams('id must be non-empty'));
    }
    return toVoid(NativeHosts.remove(id));
  },

  touch(id) {
    if (typeof id !== 'string' || id.length === 0) {
      return Promise.reject(invalidParams('id must be non-empty'));
    }
    return toVoid(NativeHosts.touch(id));
  },

  takePendingPairingURI() {
    return NativePairing.takePending().then(
      data => (typeof data.uri === 'string' ? data.uri : ''),
      e => {
        throw fromRejection(e);
      },
    );
  },
};

export function getHosts(): RemotlyHosts {
  return hosts;
}
