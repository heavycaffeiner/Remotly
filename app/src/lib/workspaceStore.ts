// App-side client for the per-host workspace store.
//
// The store is native (com.remotly.app.workspace.WorkspaceStore): one atomic
// JSON document per host. The document's meaning belongs to `lib/workspace`
// (serialize/parse); this module only moves bytes across the bridge and maps
// native failures into RemotlyError.
import NativeWorkspace from '../specs/NativeRemotlyWorkspace';
import { makeRemotlyError } from './errors';
import type { RemotlyErrorKind } from './errors';

const INVALID_PARAMS = 'workspace-invalid-params';

// -3 is a caller mistake (bad host id or document shape); anything else is a
// storage fault, whose copy says the data was not changed.
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

function checkHostId(hostId: string): void {
  if (typeof hostId !== 'string' || hostId.length < 1 || hostId.length > 64) {
    throw invalidParams('hostId must be a string of 1..64 chars');
  }
}

/** The stored document, or '' when the host has no workspace yet. */
export function loadWorkspaceDocument(hostId: string): Promise<string> {
  try {
    checkHostId(hostId);
  } catch (e) {
    return Promise.reject(e);
  }
  return NativeWorkspace.load(hostId).then(
    d => (typeof d.json === 'string' ? d.json : ''),
    e => {
      throw fromRejection(e);
    },
  );
}

/** Persists the document. The native side re-validates the structure. */
export function saveWorkspaceDocument(
  hostId: string,
  json: string,
): Promise<void> {
  try {
    checkHostId(hostId);
    if (typeof json !== 'string' || json === '') {
      throw invalidParams('json must be a non-empty string');
    }
  } catch (e) {
    return Promise.reject(e);
  }
  return toVoid(NativeWorkspace.save(hostId, json));
}

/** Deletes the host's document. Called when the host record is removed. */
export function clearWorkspaceDocument(hostId: string): Promise<void> {
  try {
    checkHostId(hostId);
  } catch (e) {
    return Promise.reject(e);
  }
  return toVoid(NativeWorkspace.clear(hostId));
}

/**
 * Frees the native terminal retained for a session.
 *
 * A terminal outlives the screen that rendered it so its scrollback survives
 * navigation. Call this when a tab is closed for good; failing to would hold
 * the scrollback for a session that can never be reopened.
 */
export function releaseTerminal(sessionId: string): Promise<void> {
  if (sessionId === '') return Promise.resolve();
  return toVoid(NativeWorkspace.releaseTerminal(sessionId));
}

/**
 * Stores the one-shot open parameter. Call immediately before router.open so
 * the workspace page finds it on mount.
 */
export function storeWorkspaceOpen(hostId: string): Promise<void> {
  try {
    checkHostId(hostId);
  } catch (e) {
    return Promise.reject(e);
  }
  return toVoid(NativeWorkspace.open(hostId));
}

/**
 * Drains the one-shot open parameter. Returns '' when nothing was stored.
 * Each call consumes the value.
 */
export function takeWorkspaceOpen(): Promise<string> {
  return NativeWorkspace.takeOpen().then(
    d => (typeof d.hostId === 'string' ? d.hostId : ''),
    e => {
      throw fromRejection(e);
    },
  );
}
