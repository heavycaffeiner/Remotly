// The real SFTP bridge: wraps the RemotlySftp TurboModule methods and adapts
// them to the SftpBridge seam in files.ts. The
// native side serializes entries and host keys as JSON strings; this module
// parses them defensively so a malformed payload degrades to an empty value
// instead of crashing the browser.

import NativeSftp from '../specs/NativeRemotlySftp';
import type { SftpBridge, SftpHostKey, SftpRawEntry } from './files';

// The module rejects with the native failure as an Error; keep that shape.
function toError(e: unknown): Error {
  return new Error((e as Error)?.message ?? 'sftp bridge call failed');
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

const EMPTY_ENTRY: SftpRawEntry = {
  name: '',
  isDirectory: false,
  isSymlink: false,
  size: 0,
  modifyTimeMillis: 0,
  permissions: 0,
};

function parseList(json: unknown): SftpRawEntry[] {
  if (typeof json !== 'string' || json === '') return [];
  try {
    const value = JSON.parse(json) as unknown;
    return Array.isArray(value) ? (value as SftpRawEntry[]) : [];
  } catch {
    return [];
  }
}

function parseEntry(json: unknown): SftpRawEntry {
  if (typeof json === 'string' && json !== '') {
    try {
      return JSON.parse(json) as SftpRawEntry;
    } catch {
      return EMPTY_ENTRY;
    }
  }
  return EMPTY_ENTRY;
}

function parseHostKey(json: unknown): SftpHostKey | undefined {
  if (typeof json === 'string' && json !== '') {
    try {
      return JSON.parse(json) as SftpHostKey;
    } catch {
      return undefined;
    }
  }
  return undefined;
}

export const sftpBridge: SftpBridge = {
  connect(hostId: string) {
    return toVoid(NativeSftp.connect(hostId));
  },
  async status(hostId: string) {
    const data = await NativeSftp.status(hostId).catch(e => {
      throw toError(e);
    });
    return {
      state: typeof data.state === 'string' ? data.state : 'NONE',
      hostKey: parseHostKey(data.hostKey),
      changed: data.changed === true,
      code: data.code,
      message: data.message,
    };
  },
  hostKey(hostId: string, accept: boolean) {
    return toVoid(NativeSftp.hostKey(hostId, accept));
  },
  async list(hostId: string, path: string) {
    const data = await NativeSftp.list(hostId, path).catch(e => {
      throw toError(e);
    });
    return parseList(data.entries);
  },
  async stat(hostId: string, path: string) {
    const data = await NativeSftp.stat(hostId, path).catch(e => {
      throw toError(e);
    });
    return parseEntry(data.entry);
  },
  async realPath(hostId: string, path: string) {
    const data = await NativeSftp.realPath(hostId, path).catch(e => {
      throw toError(e);
    });
    return typeof data.path === 'string' ? data.path : '';
  },
  mkdir(hostId: string, path: string) {
    return toVoid(NativeSftp.mkdir(hostId, path));
  },
  rename(hostId: string, from: string, to: string) {
    return toVoid(NativeSftp.rename(hostId, from, to));
  },
  remove(hostId: string, path: string, isDir: boolean) {
    return toVoid(NativeSftp.remove(hostId, path, isDir));
  },
  close(hostId: string) {
    return toVoid(NativeSftp.close(hostId));
  },
};

/** How often the session state is sampled while it settles. */
const READY_POLL_MS = 150;

/** Bounds the wait, so a session that never settles fails instead of hanging. */
const READY_POLL_MAX = 60;

/**
 * Connects the host's SFTP session if needed and waits for it to be usable.
 *
 * The browser drives this itself because it renders each state, but callers
 * that only need one operation (uploading a pasted image, say) have no UI for
 * a half-open session and need it either ready or failed.
 *
 * A pending host-key prompt is reported rather than answered: accepting a key
 * on the user's behalf is exactly the decision that must not be automated, so
 * this refuses and points them at the file browser, which can show the
 * fingerprint and ask.
 */
export async function ensureSftpReady(hostId: string): Promise<void> {
  const current = await sftpBridge.status(hostId);
  if (current.state !== 'READY') await sftpBridge.connect(hostId);

  for (let i = 0; i < READY_POLL_MAX; i++) {
    const st = await sftpBridge.status(hostId);
    if (st.state === 'READY') return;
    if (st.state === 'FAILED') {
      throw new Error(st.message ?? 'The SFTP connection failed.');
    }
    if (st.state === 'HOST_KEY') {
      throw new Error(
        'This host key needs review. Open the Files tab to check it.',
      );
    }
    await new Promise(r => setTimeout(r, READY_POLL_MS));
  }
  throw new Error('Timed out waiting for the SFTP session.');
}
