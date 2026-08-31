// App-side boundary for an SSH terminal session. Wraps the native RemotlySsh
// TurboModule and adapts the state and data events to a callback seam the SSH
// terminal screen consumes. connect starts the session and returns
// immediately; the terminal bytes and lifecycle arrive through onEvent.
//
// Every event carries the host id and the session id it belongs to. Listeners
// subscribe for one (host, session) pair and never observe another's state or
// output. One host can back several tabs at once, and cross-delivered output
// would mean one session's terminal content rendered into another's.

import NativeSsh from '../specs/NativeRemotlySsh';
import { decodeBase64, encodeBase64 } from './base64';
import { log } from './log';

export type SshStateKind =
  | 'disconnected'
  | 'connecting'
  | 'hostKey'
  | 'active'
  | 'closed'
  | 'failed';

const STATE_KINDS: readonly SshStateKind[] = [
  'disconnected',
  'connecting',
  'hostKey',
  'active',
  'closed',
  'failed',
];

export interface SshState {
  state: SshStateKind;
  // hostKey prompt.
  algorithm?: string;
  fingerprint?: string;
  changed?: boolean;
  // closed / failed.
  code?: string;
  /** The operation that failed. Present on `failed` when the engine reports it. */
  stage?: SshFailureStage;
  reason?: string;
  userInitiated?: boolean;
}

/** The operation that failed, for diagnosing an interoperability problem. */
export type SshFailureStage =
  | 'ssh_dial_failed'
  | 'ssh_handshake_failed'
  | 'ssh_auth_failed'
  | 'ssh_host_key_rejected'
  | 'ssh_channel_failed'
  | 'ssh_pty_failed'
  | 'ssh_shell_failed'
  | 'ssh_remote_closed'
  | 'ssh_timeout'
  | 'ssh_cancelled';

const FAILURE_STAGES: readonly SshFailureStage[] = [
  'ssh_dial_failed',
  'ssh_handshake_failed',
  'ssh_auth_failed',
  'ssh_host_key_rejected',
  'ssh_channel_failed',
  'ssh_pty_failed',
  'ssh_shell_failed',
  'ssh_remote_closed',
  'ssh_timeout',
  'ssh_cancelled',
];

/**
 * Actionable copy for a failure stage.
 *
 * A stage says which step broke, which is what turns "could not connect" into
 * something the user can act on.
 */
export function stageMessage(
  stage: SshFailureStage | undefined,
): string | null {
  switch (stage) {
    case 'ssh_dial_failed':
      return 'The host did not accept a connection. Check the address, the port, and that the SSH server is running.';
    case 'ssh_handshake_failed':
      return 'The SSH handshake did not complete. The server may not be an SSH server, or it offered no algorithm in common.';
    case 'ssh_auth_failed':
      return 'The server rejected the credential. Check the username, and the password or key.';
    case 'ssh_host_key_rejected':
      return 'The host key was rejected, so the connection was not trusted.';
    case 'ssh_channel_failed':
      return 'The server refused to open a session channel.';
    case 'ssh_pty_failed':
      return 'The server refused a terminal. It may be configured for file transfer only.';
    case 'ssh_shell_failed':
      return 'The server accepted a terminal but could not start a shell.';
    case 'ssh_timeout':
      return 'The server stopped responding during the connection.';
    default:
      return null;
  }
}

export type SshHostKeyDecision = 'accept' | 'replace' | 'reject';

// The module rejects with the native failure as an Error; keep that shape.
function toError(e: unknown): Error {
  return new Error((e as Error)?.message ?? 'ssh bridge call failed');
}

// Resolves undefined on success, re-throws the native failure on rejection.
function toVoid(p: Promise<unknown>): Promise<void> {
  return p.then(
    () => undefined,
    e => {
      throw toError(e);
    },
  );
}

type Emitter = (handler: (arg: unknown) => void) => { remove(): void };

export interface RemotlySsh {
  connect(
    hostId: string,
    sessionId: string,
    cols: number,
    rows: number,
  ): Promise<void>;
  write(hostId: string, sessionId: string, data: Uint8Array): Promise<void>;
  resize(
    hostId: string,
    sessionId: string,
    cols: number,
    rows: number,
  ): Promise<void>;
  hostKey(
    hostId: string,
    sessionId: string,
    decision: SshHostKeyDecision,
  ): Promise<void>;
  close(hostId: string, sessionId: string): Promise<void>;
  /** Closes every session for a host. */
  closeHost(hostId: string): Promise<void>;
  /** Subscribes to state changes for one session. */
  onState(
    hostId: string,
    sessionId: string,
    handler: (state: SshState) => void,
  ): () => void;
  /** Subscribes to terminal output for one session. */
  onData(
    hostId: string,
    sessionId: string,
    handler: (bytes: Uint8Array) => void,
  ): () => void;
}

// Stores the one-shot open parameter, drained by the terminal screen on mount.
export function storeSshOpen(hostId: string): Promise<void> {
  return toVoid(NativeSsh.storeOpen(hostId));
}

// Drains the one-shot open parameter. Returns '' when nothing was stored.
export function takeSshOpen(): Promise<string> {
  return NativeSsh.takeOpen().then(
    d => (typeof d.hostId === 'string' ? d.hostId : ''),
    e => {
      throw toError(e);
    },
  );
}

export function normalizeState(data: Record<string, unknown>): SshState {
  const raw = data.state;
  const state =
    typeof raw === 'string' && STATE_KINDS.includes(raw as SshStateKind)
      ? (raw as SshStateKind)
      : 'disconnected';
  const out: SshState = { state };
  if (typeof data.algorithm === 'string') out.algorithm = data.algorithm;
  if (typeof data.fingerprint === 'string') out.fingerprint = data.fingerprint;
  if (typeof data.changed === 'boolean') out.changed = data.changed;
  if (typeof data.reason === 'string') out.reason = data.reason;
  if (typeof data.userInitiated === 'boolean')
    out.userInitiated = data.userInitiated;
  if (data.code !== undefined && data.code !== null)
    out.code = String(data.code);
  const stage = FAILURE_STAGES.find(s => s === data.stage);
  if (stage !== undefined) out.stage = stage;
  return out;
}

/** Reads the host id an event belongs to, or '' when it carries none. */
export function eventHostId(event: unknown): string {
  if (event === null || typeof event !== 'object') return '';
  const id = (event as Record<string, unknown>).hostId;
  return typeof id === 'string' ? id : '';
}

/** Reads the session id an event belongs to, or '' when it carries none. */
export function eventSessionId(event: unknown): string {
  if (event === null || typeof event !== 'object') return '';
  const id = (event as Record<string, unknown>).sessionId;
  return typeof id === 'string' ? id : '';
}

// Subscribes to one emitter and filters to a single (host, session) pair. An
// event missing either id is dropped rather than broadcast, because delivering
// it to every listener is exactly the cross-talk this filtering prevents.
function subscribeForSession(
  emitter: Emitter,
  hostId: string,
  sessionId: string,
  kind: 'state' | 'data',
  deliver: (event: Record<string, unknown>) => void,
): () => void {
  const sub = emitter(event => {
    if (event === null || event === undefined) return;
    if (eventHostId(event) !== hostId) return;
    if (eventSessionId(event) !== sessionId) return;
    try {
      deliver(event as Record<string, unknown>);
    } catch (e) {
      log.warn('ssh event handler failed', { event: kind, error: String(e) });
    }
  });
  return () => {
    sub.remove();
  };
}

export const remotlySsh: RemotlySsh = {
  connect(hostId, sessionId, cols, rows) {
    return toVoid(NativeSsh.connect(hostId, sessionId, cols, rows));
  },
  write(hostId, sessionId, data) {
    if (data.length === 0) return Promise.resolve();
    return toVoid(NativeSsh.write(hostId, sessionId, encodeBase64(data)));
  },
  resize(hostId, sessionId, cols, rows) {
    return toVoid(NativeSsh.resize(hostId, sessionId, cols, rows));
  },
  hostKey(hostId, sessionId, decision) {
    return toVoid(NativeSsh.hostKey(hostId, sessionId, decision));
  },
  close(hostId, sessionId) {
    return toVoid(NativeSsh.close(hostId, sessionId));
  },
  closeHost(hostId) {
    return toVoid(NativeSsh.closeHost(hostId));
  },
  onState(hostId, sessionId, handler) {
    return subscribeForSession(
      NativeSsh.onState as unknown as Emitter,
      hostId,
      sessionId,
      'state',
      event => handler(normalizeState(event)),
    );
  },
  onData(hostId, sessionId, handler) {
    return subscribeForSession(
      NativeSsh.onData as unknown as Emitter,
      hostId,
      sessionId,
      'data',
      event => {
        const b64 = event.data;
        if (typeof b64 === 'string' && b64 !== '') handler(decodeBase64(b64));
      },
    );
  },
};
