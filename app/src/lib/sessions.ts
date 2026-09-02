// Control-plane helpers for daemon sessions.
//
// These wrap getTransport().control with the raw daemon JSON (snake_case)
// that crosses the bridge. Daemon error responses resolve the bridge call
// normally as {type: "error", error: {code, message}}, so every helper
// checks for that shape and throws a DaemonError carrying the stable string
// code; transport-level failures reject with the usual RemotlyError.
//
// Every daemon value is untrusted input: ids, channel ids, continuity,
// cursors, and metadata are validated or clamped here, at the boundary.

import { getTransport, rfc3339ToMillis } from './transport';
import { isValidSessionId, MAX_CURSOR } from './workspace';

export class DaemonError extends Error {
  /** Stable daemon error code, such as "cursor_out_of_range". */
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.name = 'DaemonError';
    this.code = code;
  }
}

/** One daemon session, cleaned at the boundary. */
export interface RawSession {
  /** The daemon's 64-hex-character session id. */
  id: string;
  title: string;
  kind: string;
  running: boolean;
  /** RFC3339 UTC, as the daemon emits it. */
  last_activity: string;
  exit?: { code: number; signal: string | null } | null;
  preview?: string;
}

/** Last activity of a raw session, in Unix milliseconds (0 when unparseable). */
export function rawSessionLastActivityMs(s: RawSession): number {
  return rfc3339ToMillis(s.last_activity);
}

/** A configured daemon session preset, from preset.list. */
export interface Preset {
  name: string;
  command: string;
  icon_hint: string;
}

/** How a replay relates to the requested cursor. */
export type Continuity = 'full' | 'gapless' | 'gap';

export interface AttachResult {
  channelId: number;
  continuity: Continuity;
  /** Output-stream byte offset the replay started at. */
  replayedFrom: number;
}

export interface CreateSessionOptions {
  kind: 'shell' | 'agent';
  /** Required for agent, forbidden for shell. */
  command?: string;
  title?: string;
  cols?: number;
  rows?: number;
}

const MAX_TITLE_LEN = 200;
const MAX_COMMAND_LEN = 4096;

function toInt(value: unknown): number {
  const n = typeof value === 'string' ? Number(value) : (value as number);
  return typeof n === 'number' && Number.isFinite(n) ? Math.trunc(n) : NaN;
}

function asString(value: unknown, max: number): string {
  return typeof value === 'string' ? value.slice(0, max) : '';
}

/**
 * Validates one raw session row and returns a cleaned copy, or null when the
 * daemon sent a malformed row. Malformed rows are dropped, never thrown.
 */
export function parseRawSession(raw: unknown): RawSession | null {
  if (raw === null || typeof raw !== 'object') return null;
  const o = raw as Record<string, unknown>;
  const id = o.id;
  if (!isValidSessionId(id)) return null;
  const exit = o.exit;
  let exitOut: { code: number; signal: string | null } | null | undefined;
  if (exit !== null && exit !== undefined) {
    if (typeof exit !== 'object') {
      exitOut = undefined;
    } else {
      const e = exit as Record<string, unknown>;
      const code = toInt(e.code);
      if (Number.isNaN(code)) {
        exitOut = undefined;
      } else {
        exitOut = {
          code,
          signal: typeof e.signal === 'string' ? e.signal.slice(0, 32) : null,
        };
      }
    }
  }
  return {
    id,
    title: asString(o.title, MAX_TITLE_LEN),
    kind: asString(o.kind, 32),
    running: o.running === true,
    last_activity: asString(o.last_activity, 64),
    ...(exitOut !== undefined ? { exit: exitOut } : {}),
    ...(typeof o.preview === 'string' && o.preview !== ''
      ? { preview: o.preview }
      : {}),
  };
}

// Runs one control request and surfaces daemon error responses as
// DaemonError. Non-error responses are returned as the raw record.
async function controlChecked(
  hostId: string,
  request: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  const resp = await getTransport().control(hostId, request);
  if (resp !== null && typeof resp === 'object' && resp.type === 'error') {
    const err = (resp as { error?: unknown }).error;
    const o =
      err !== null && typeof err === 'object'
        ? (err as Record<string, unknown>)
        : {};
    throw new DaemonError(asString(o.code, 64), asString(o.message, 512));
  }
  return resp;
}

/** The daemon's current sessions, oldest first. Malformed rows are dropped. */
export async function listSessions(hostId: string): Promise<RawSession[]> {
  const resp = await controlChecked(hostId, { type: 'session.list' });
  const arr = Array.isArray((resp as { sessions?: unknown }).sessions)
    ? (resp as { sessions: unknown[] }).sessions
    : [];
  const out: RawSession[] = [];
  const seen = new Set<string>();
  for (const raw of arr) {
    const s = parseRawSession(raw);
    if (s === null || seen.has(s.id)) continue;
    seen.add(s.id);
    out.push(s);
  }
  return out;
}

/**
 * Opens a term channel on a session. With resumeFrom the replay starts at
 * that byte offset; on cursor_out_of_range the caller should retry without
 * a cursor.
 */
export async function attachSession(
  hostId: string,
  sessionId: string,
  resumeFrom?: number,
): Promise<AttachResult> {
  if (!isValidSessionId(sessionId)) {
    throw new DaemonError('invalid_request', 'bad session id');
  }
  const request: Record<string, unknown> = {
    type: 'session.attach',
    session_id: sessionId,
  };
  if (resumeFrom !== undefined) {
    if (
      !Number.isInteger(resumeFrom) ||
      resumeFrom < 0 ||
      resumeFrom > MAX_CURSOR
    ) {
      throw new DaemonError('invalid_request', 'bad resume cursor');
    }
    request.resume_from = resumeFrom;
  }
  const resp = await controlChecked(hostId, request);
  const channelId = toInt((resp as { channel_id?: unknown }).channel_id);
  const continuity = (resp as { continuity?: unknown }).continuity;
  const replayedFrom = toInt(
    (resp as { replayed_from?: unknown }).replayed_from,
  );
  if (Number.isNaN(channelId) || channelId < 1 || channelId > 0xffffffff) {
    throw new DaemonError('invalid_request', 'bad channel id');
  }
  if (
    continuity !== 'full' &&
    continuity !== 'gapless' &&
    continuity !== 'gap'
  ) {
    throw new DaemonError('invalid_request', 'bad continuity');
  }
  if (
    Number.isNaN(replayedFrom) ||
    replayedFrom < 0 ||
    replayedFrom > MAX_CURSOR
  ) {
    throw new DaemonError('invalid_request', 'bad replay offset');
  }
  return { channelId, continuity, replayedFrom };
}

/** Starts a new session and returns its meta. */
export async function createSession(
  hostId: string,
  opts: CreateSessionOptions,
): Promise<RawSession> {
  if (opts.kind !== 'shell' && opts.kind !== 'agent') {
    throw new DaemonError('invalid_request', 'bad kind');
  }
  const request: Record<string, unknown> = {
    type: 'session.create',
    kind: opts.kind,
  };
  if (opts.kind === 'agent') {
    const command = typeof opts.command === 'string' ? opts.command : '';
    if (command.trim() === '' || command.length > MAX_COMMAND_LEN) {
      throw new DaemonError('invalid_request', 'bad command');
    }
    request.command = command;
  }
  if (opts.title !== undefined) {
    if (opts.title === '' || opts.title.length > MAX_TITLE_LEN) {
      throw new DaemonError('invalid_request', 'bad title');
    }
    request.title = opts.title;
  }
  for (const dim of [opts.cols, opts.rows]) {
    if (
      dim !== undefined &&
      (!Number.isInteger(dim) || dim < 1 || dim > 1000)
    ) {
      throw new DaemonError('invalid_request', 'bad dimension');
    }
  }
  if (opts.cols !== undefined) request.cols = opts.cols;
  if (opts.rows !== undefined) request.rows = opts.rows;

  const resp = await controlChecked(hostId, request);
  const s = parseRawSession((resp as { session?: unknown }).session);
  if (s === null) {
    throw new DaemonError('invalid_request', 'bad session meta');
  }
  return s;
}

/** Ends one attached channel. The daemon session keeps running. */
export async function detachChannel(
  hostId: string,
  channelId: number,
): Promise<void> {
  if (!Number.isInteger(channelId) || channelId < 1 || channelId > 0xffffffff) {
    throw new DaemonError('invalid_request', 'bad channel id');
  }
  await controlChecked(hostId, {
    type: 'session.detach',
    channel_id: channelId,
  });
}

/**
 * Ends a session on the daemon and terminates its process.
 *
 * Detaching only gives up the channel: the shell keeps running so it can be
 * reattached later, which is the point of the daemon. Closing a tab is the
 * user saying they are finished with it, and without this the session stayed
 * alive with nothing referring to it.
 */
export async function killSession(
  hostId: string,
  sessionId: string,
): Promise<void> {
  if (!isValidSessionId(sessionId)) {
    throw new DaemonError('invalid_request', 'bad session id');
  }
  await controlChecked(hostId, {
    type: 'session.kill',
    session_id: sessionId,
  });
}

/**
 * Renames a session on the daemon.
 *
 * The daemon owns the name so every client and every reconnect sees it, which
 * a purely local label would not survive.
 */
export async function renameSession(
  hostId: string,
  sessionId: string,
  title: string,
): Promise<void> {
  if (!isValidSessionId(sessionId)) {
    throw new DaemonError('invalid_request', 'bad session id');
  }
  const name = title.trim();
  if (name === '' || name.length > MAX_TITLE_LEN) {
    throw new DaemonError('invalid_request', 'bad title');
  }
  await controlChecked(hostId, {
    type: 'session.rename',
    session_id: sessionId,
    title: name,
  });
}

/** Changes a session's PTY size. */
export async function resizeSession(
  hostId: string,
  sessionId: string,
  cols: number,
  rows: number,
): Promise<void> {
  if (!isValidSessionId(sessionId)) {
    throw new DaemonError('invalid_request', 'bad session id');
  }
  if (
    !Number.isInteger(cols) ||
    !Number.isInteger(rows) ||
    cols < 1 ||
    cols > 1000 ||
    rows < 1 ||
    rows > 1000
  ) {
    throw new DaemonError('invalid_request', 'bad dimension');
  }
  await controlChecked(hostId, {
    type: 'session.resize',
    session_id: sessionId,
    cols,
    rows,
  });
}

/** The daemon's configured session presets. */
export async function listPresets(hostId: string): Promise<Preset[]> {
  const resp = await controlChecked(hostId, { type: 'preset.list' });
  const arr = Array.isArray((resp as { presets?: unknown }).presets)
    ? (resp as { presets: unknown[] }).presets
    : [];
  const out: Preset[] = [];
  const seen = new Set<string>();
  for (const raw of arr) {
    if (raw === null || typeof raw !== 'object') continue;
    const o = raw as Record<string, unknown>;
    const name = asString(o.name, 50);
    const command = asString(o.command, MAX_COMMAND_LEN);
    if (name === '' || command === '' || seen.has(name)) continue;
    seen.add(name);
    out.push({ name, command, icon_hint: asString(o.icon_hint, 32) });
  }
  return out;
}
