// App-side boundary for the secure transport to a Remotly daemon.
//
// M1-10 replaces the unavailable stub with a bridge-backed implementation.
// The Noise handshake, WebSocket framing, and channel multiplexing live in
// the native module (com.remotly.app.transport). This module is the only JS
// surface the app talks to: it calls the IDL bridge methods, converts the
// bridge's base64 payloads to raw bytes, and maps native failures into
// RemotlyError so screens never see raw bridge codes or internal reasons.
import NativeTransport from '../specs/NativeRemotlyTransport';
import { decodeBase64, encodeBase64 } from './base64';
import { kindFromCloseCode, makeRemotlyError } from './errors';
import type { RemotlyError, RemotlyErrorKind } from './errors';
import { log } from './log';

/** Credentials for `connect`. Exactly one path is valid. */
export interface TransportConnectOptions {
  /** base64url (unpadded) 32-byte daemon public key. Pinned-key path. */
  daemonPub?: string;
  /** base64url (unpadded) pairing token id. Pairing path. */
  tokenID?: string;
  /** base64url (unpadded) 32-byte pairing secret. Pairing path. */
  psk?: string;
  /**
   * Relay fallback target ("host:port"). When set with relayId, the app tries
   * the direct target first and falls back to this relay if the direct attempt
   * fails before connecting. Absent when the host has no relay hint.
   */
  relayTarget?: string;
  /** base64url (unpadded) 16-byte relay id (first 16 bytes of daemonPub). */
  relayId?: string;
  /**
   * When true and relayTarget/relayId are set, the relay is the only attempt
   * (the direct target is skipped). Used after the caller has already tried
   * every direct hint.
   */
  relayOnly?: boolean;
}

export interface TransportConnection {
  daemonName: string;
  daemonPub?: string;
  /** "direct" or "relay": how the connection reached the daemon. */
  via?: 'direct' | 'relay';
}

export interface TransportStatus {
  connected: boolean;
  state: 'disconnected' | 'connecting' | 'connected';
  daemonName?: string;
  daemonPub?: string;
  /** "direct" or "relay" when connected; absent otherwise. */
  via?: 'direct' | 'relay';
}

/** A daemon session as reported by sessionUpdate and session.list. */
export interface SessionMeta {
  /** The daemon's 64-hex-character session id. */
  id: string;
  title: string;
  kind: string;
  command: string;
  cwd: string;
  cols: number;
  rows: number;
  /** RFC3339 UTC timestamps, as the daemon emits them. */
  createdAt: string;
  lastActivity: string;
  running: boolean;
  exit?: { code: number; signal: string | null };
  /** Last visible line of the session, sanitized by the daemon. */
  preview?: string;
}

/**
 * Parses an RFC3339 UTC timestamp to Unix milliseconds, or 0 when the value
 * is not a valid date. Both session timestamp paths (the hub's sessionUpdate
 * and the raw session.list JSON) carry RFC3339 strings.
 */
export function rfc3339ToMillis(value: unknown): number {
  if (typeof value !== 'string') return 0;
  const ms = Date.parse(value);
  return Number.isFinite(ms) ? ms : 0;
}

/**
 * A session event notification (bell or pattern match) pushed by the daemon.
 * `sessionId` is the daemon's 64-hex-character session id. `text` is terminal
 * content: it is shown in the in-app banner only and never logged or sent to
 * analytics.
 */
export interface SessionEvent {
  hostId: string;
  sessionId: string;
  seq: number;
  kind: 'bell' | 'pattern';
  pattern?: string;
  text?: string;
  /** Unix seconds. */
  ts: number;
}

/**
 * Events pushed from the native hub. Every payload carries the `hostId` of
 * the connection that produced it, so one container can hold several hosts.
 * `termData.data` is raw terminal output for the channel; the base64 the
 * bridge carries is already decoded here.
 */
export interface TransportEvents {
  connected: {
    hostId: string;
    daemonName: string;
    daemonPub?: string;
    via?: 'direct' | 'relay';
  };
  disconnected: { hostId: string; code: number; reason: string };
  sessionUpdate: { hostId: string; session: SessionMeta };
  channelClose: { hostId: string; channelId: number; reason: string };
  /**
   * The replay/live boundary of one term channel was crossed. `offset` is
   * the resume cursor at the boundary. The mux serves control frames with
   * priority, so this may arrive before the final replay bytes: clients
   * track the cursor as replayed_from plus the term bytes received.
   */
  replayComplete: {
    hostId: string;
    channelId: number;
    offset: number;
    gap?: boolean;
  };
  termData: {
    hostId: string;
    channelId: number;
    data: Uint8Array;
    length?: number;
    fastPath?: boolean;
  };
  /** Raw file-channel frame for one transfer channel (a download chunk the
   *  daemon pumped). The base64 the bridge carries is already decoded here. */
  fileData: { hostId: string; channelId: number; data: Uint8Array };
  sessionEvent: SessionEvent;
}

export interface RemotlyTransport {
  /** True: the real bridge-backed transport is wired in (M1-10). */
  readonly available: boolean;
  /**
   * Open a secure session for one host. Resolves when the handshake (hello)
   * completes. hostId is the app-side key for the connection; it is the
   * paired host's stored id, or a temporary id during pairing.
   */
  connect(
    hostId: string,
    target: string,
    options: TransportConnectOptions,
  ): Promise<TransportConnection>;
  /** Close one host's session. Resolves immediately when none is open. */
  close(hostId: string): Promise<void>;
  /** Snapshot of one host's connection state. */
  status(hostId: string): Promise<TransportStatus>;
  /** Send one control request on one host and resolve with the response. */
  control(
    hostId: string,
    request: Record<string, unknown>,
  ): Promise<Record<string, unknown>>;
  /** Write terminal input to an attached channel (fire and forget). */
  writeTerm(hostId: string, channelId: number, data: Uint8Array): Promise<void>;
  /**
   * Register the file channel a transfer.create/resume opened, so chunk frames
   * on it are accepted. Resolves when the registration is handed to the
   * connection (a no-op when there is no ready connection).
   */
  openFile(hostId: string, channelId: number): Promise<void>;
  /**
   * Write one file-channel frame to the daemon (an upload chunk: [8-byte
   * big-endian offset][payload]), fire and forget.
   */
  writeFile(hostId: string, channelId: number, data: Uint8Array): Promise<void>;
  /** Subscribe to a hub event. Returns an unsubscribe function. */
  onEvent<K extends keyof TransportEvents>(
    name: K,
    handler: (event: TransportEvents[K]) => void,
  ): () => void;
}

const INVALID_PARAMS = 'transport-invalid-params';

// Maps a bridge failure code to an error kind. 4000-4004 are the protocol
// close range; 1006 (abnormal close) and the IDL network codes are network
// failures; anything else (IDL FAIL, invalid param, missing native module)
// is unknown.
function kindFromBridgeCode(code: number): RemotlyErrorKind {
  if (code >= 4000 && code <= 4004) return kindFromCloseCode(code);
  if (code === 1006 || code === -1001 || code === -1002) return 'network';
  return 'unknown';
}

function bridgeError(code: number, msg: string) {
  return makeRemotlyError(kindFromBridgeCode(code), code, new Error(msg));
}

function invalidParams(message: string) {
  return makeRemotlyError('unknown', INVALID_PARAMS, new Error(message));
}

// Adapts a TurboModule rejection to a branded RemotlyError, preserving the
// numeric bridge code (the module rejects with the code as a string).
function fromRejection(e: unknown): RemotlyError {
  const code = Number((e as { code?: string })?.code ?? 0);
  const msg = (e as Error)?.message ?? 'bridge call failed';
  return bridgeError(code, msg);
}

// Resolves undefined on success, re-throws a branded RemotlyError on failure.
function toVoid(p: Promise<unknown>): Promise<void> {
  return p.then(
    () => undefined,
    e => {
      throw fromRejection(e);
    },
  );
}

type Emitter = (handler: (arg: unknown) => void) => { remove(): void };

const transport: RemotlyTransport = {
  available: true,

  connect(hostId, target, options) {
    if (typeof hostId !== 'string' || hostId.trim() === '') {
      return Promise.reject(invalidParams('hostId must be a non-empty string'));
    }
    const trimmed = target.trim();
    if (trimmed === '') {
      return Promise.reject(
        invalidParams('target must be a non-empty host or host:port'),
      );
    }
    const pinned = options.daemonPub !== undefined;
    // A pairing is valid only when both halves are present; a lone tokenID or
    // psk is rejected here, not after a round trip to the bridge.
    const paired = options.tokenID !== undefined && options.psk !== undefined;
    if (!pinned && !paired) {
      return Promise.reject(
        invalidParams(
          'connect needs a pinned daemonPub or a tokenID+psk pairing',
        ),
      );
    }
    return NativeTransport.connect(hostId, trimmed, options).then(
      r => r as TransportConnection,
      e => {
        throw fromRejection(e);
      },
    );
  },

  close(hostId) {
    if (typeof hostId !== 'string' || hostId === '') {
      return Promise.reject(invalidParams('hostId must be a non-empty string'));
    }
    return toVoid(NativeTransport.close(hostId));
  },

  status(hostId) {
    if (typeof hostId !== 'string' || hostId === '') {
      return Promise.reject(invalidParams('hostId must be a non-empty string'));
    }
    return NativeTransport.status(hostId).then(
      r => r as unknown as TransportStatus,
      e => {
        throw fromRejection(e);
      },
    );
  },

  control(hostId, request) {
    if (typeof hostId !== 'string' || hostId === '') {
      return Promise.reject(invalidParams('hostId must be a non-empty string'));
    }
    let requestJson: string;
    try {
      requestJson = JSON.stringify(request);
    } catch {
      return Promise.reject(
        invalidParams('control request must be serializable'),
      );
    }
    return NativeTransport.control(hostId, requestJson)
      .catch(e => {
        throw fromRejection(e);
      })
      .then(data => {
        try {
          return JSON.parse(data.response) as Record<string, unknown>;
        } catch {
          throw bridgeError(4002, 'control response is not valid JSON');
        }
      });
  },

  writeTerm(hostId, channelId, data) {
    if (typeof hostId !== 'string' || hostId === '') {
      return Promise.reject(invalidParams('hostId must be a non-empty string'));
    }
    if (
      !Number.isInteger(channelId) ||
      channelId < 1 ||
      channelId > 0xffffffff
    ) {
      return Promise.reject(
        invalidParams('channelId must be an integer in 1..2^32-1'),
      );
    }
    if (!(data instanceof Uint8Array)) {
      return Promise.reject(invalidParams('data must be a Uint8Array'));
    }
    if (data.length === 0) return Promise.resolve();
    return toVoid(
      NativeTransport.writeTerm(hostId, channelId, encodeBase64(data)),
    );
  },

  openFile(hostId, channelId) {
    if (typeof hostId !== 'string' || hostId === '') {
      return Promise.reject(invalidParams('hostId must be a non-empty string'));
    }
    if (
      !Number.isInteger(channelId) ||
      channelId < 1 ||
      channelId > 0xffffffff
    ) {
      return Promise.reject(
        invalidParams('channelId must be an integer in 1..2^32-1'),
      );
    }
    return toVoid(NativeTransport.openFile(hostId, channelId));
  },

  writeFile(hostId, channelId, data) {
    if (typeof hostId !== 'string' || hostId === '') {
      return Promise.reject(invalidParams('hostId must be a non-empty string'));
    }
    if (
      !Number.isInteger(channelId) ||
      channelId < 1 ||
      channelId > 0xffffffff
    ) {
      return Promise.reject(
        invalidParams('channelId must be an integer in 1..2^32-1'),
      );
    }
    if (!(data instanceof Uint8Array)) {
      return Promise.reject(invalidParams('data must be a Uint8Array'));
    }
    if (data.length === 0) return Promise.resolve();
    return toVoid(
      NativeTransport.writeFile(hostId, channelId, encodeBase64(data)),
    );
  },

  onEvent(name, handler) {
    const raw: (event: unknown) => void = event => {
      if (event === null || event === undefined) return;
      try {
        handler(normalizeEvent(name, event as Record<string, unknown>));
      } catch (e) {
        log.warn('transport event handler failed', {
          event: name,
          error: String(e),
        });
      }
    };
    const sub = emitterFor(name)(raw);
    return () => {
      sub.remove();
    };
  },
};

// Maps a TransportEvents key to its codegen emitter property. Events arrive
// as the payload object directly from codegen; the handler wraps it.
function emitterFor(name: keyof TransportEvents): Emitter {
  switch (name) {
    case 'termData':
      return NativeTransport.onTermData as Emitter;
    case 'fileData':
      return NativeTransport.onFileData as Emitter;
    case 'channelClose':
      return NativeTransport.onChannelClose as Emitter;
    case 'replayComplete':
      return NativeTransport.onReplayComplete as Emitter;
    case 'sessionEvent':
      return NativeTransport.onSessionEvent as Emitter;
    case 'connected':
      return NativeTransport.onConnected as Emitter;
    case 'disconnected':
      return NativeTransport.onDisconnected as Emitter;
    case 'sessionUpdate':
      return NativeTransport.onSessionUpdate as Emitter;
  }
}

// The bridge may deliver ids, seqs, and timestamps as strings or numbers;
// normalize to a safe integer (0 when missing or non-finite).
function toSafeInt(value: unknown): number {
  const n = typeof value === 'string' ? Number(value) : (value as number);
  return Number.isFinite(n) ? Math.trunc(n) : 0;
}

// The bridge is trusted to send well-formed event data (we built it). The
// only conversions are channelId to number and termData base64 to bytes.
function toHostId(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function normalizeEvent<K extends keyof TransportEvents>(
  name: K,
  data: Record<string, unknown>,
): TransportEvents[K] {
  const hostId = toHostId(data.hostId);
  switch (name) {
    case 'termData': {
      const b64 = typeof data.data === 'string' ? data.data : '';
      const fastPath = data.fastPath === true;
      const length =
        typeof data.length === 'number'
          ? data.length
          : b64 === ''
          ? 0
          : decodeBase64(b64).length;
      return {
        hostId,
        channelId: toSafeInt(data.channelId),
        data: fastPath || b64 === '' ? new Uint8Array(0) : decodeBase64(b64),
        length,
        fastPath,
      } as TransportEvents[K];
    }
    case 'fileData':
      return {
        hostId,
        channelId: toSafeInt(data.channelId),
        data: decodeBase64(typeof data.data === 'string' ? data.data : ''),
      } as TransportEvents[K];
    case 'channelClose':
      return {
        hostId,
        channelId: toSafeInt(data.channelId),
        reason: typeof data.reason === 'string' ? data.reason : '',
      } as TransportEvents[K];
    case 'replayComplete':
      return {
        hostId,
        channelId: toSafeInt(data.channelId),
        offset: toSafeInt(data.offset),
        ...(typeof data === 'object' &&
        data !== null &&
        'gap' in data &&
        data.gap === true
          ? { gap: true }
          : {}),
      } as TransportEvents[K];
    case 'sessionEvent':
      return {
        hostId,
        sessionId: toHostId(data.sessionId),
        seq: toSafeInt(data.seq),
        kind: data.kind === 'pattern' ? 'pattern' : 'bell',
        pattern: typeof data.pattern === 'string' ? data.pattern : undefined,
        text: typeof data.text === 'string' ? data.text : undefined,
        ts: toSafeInt(data.ts),
      } as TransportEvents[K];
    default:
      return data as TransportEvents[K];
  }
}

export function getTransport(): RemotlyTransport {
  return transport;
}
