// SSH host management (M4-02 UI). Wraps the native remotly.sshhost.* IDL
// methods. The record returned by list/add carries display data plus the
// accepted host keys; the credential bytes never cross back (they live in the
// keystore-backed SecretStore on the native side).

import NativeSshHost from '../specs/NativeRemotlySshHost';

export interface SshHostKeyView {
  algorithm: string;
  fingerprint: string;
}

export interface SshHostView {
  id: string;
  displayName: string;
  host: string;
  port: number;
  username: string;
  // 0 = password, 1 = key.
  authKind: number;
  hasCredential: boolean;
  knownKeys: SshHostKeyView[];
  createdAt: number;
  updatedAt: number;
}

export interface SshHostCredentialParams {
  useKey: boolean;
  password?: string;
  // base64 of the PEM-encoded private key (key auth only).
  privateKey?: string;
  passphrase?: string;
}

export interface SshHostAddParams extends SshHostCredentialParams {
  displayName: string;
  host: string;
  port: number;
  username: string;
}

/**
 * A partial update. An omitted field is left unchanged; an empty string is a
 * real value and clears the field.
 */
export interface SshHostUpdateParams {
  displayName?: string;
  host?: string;
  port?: number;
  username?: string;
  /** Supply to rotate the credential. Omit to leave it sealed and untouched. */
  credential?: SshHostCredentialParams;
  /** Drops the accepted host keys. Requires explicit user confirmation. */
  clearKnownKeys?: boolean;
}

export interface SshHostTestParams {
  /** Supply when editing, so an accepted host key is checked too. */
  hostId?: string;
  host: string;
  port: number;
  username: string;
  credential: SshHostCredentialParams;
}

export interface SshHostTestResult {
  ok: boolean;
  code: string;
  stage: string;
  message: string;
  hostKeyAlgorithm: string;
  hostKeyFingerprint: string;
  hostKeyKnown: boolean;
  hostKeyChanged: boolean;
}

/**
 * The name to show for a host.
 *
 * The label is optional, so the fallback is derived rather than stored. Storing
 * it would tie the label to the endpoint and silently rewrite it on an edit.
 */
export function sshHostDisplayName(host: {
  displayName: string;
  username: string;
  host: string;
}): string {
  const label = host.displayName.trim();
  return label !== '' ? label : `${host.username}@${host.host}`;
}

// The module rejects with the native failure as an Error; keep that shape.
function toError(e: unknown): Error {
  return new Error((e as Error)?.message ?? 'sshhost bridge call failed');
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

const EMPTY_HOST: SshHostView = {
  id: '',
  displayName: '',
  host: '',
  port: 0,
  username: '',
  authKind: 0,
  hasCredential: false,
  knownKeys: [],
  createdAt: 0,
  updatedAt: 0,
};

function toKeys(value: unknown): SshHostKeyView[] {
  if (!Array.isArray(value)) return [];
  return value
    .filter(
      (k): k is Record<string, unknown> => k !== null && typeof k === 'object',
    )
    .map(k => ({
      algorithm: typeof k.algorithm === 'string' ? k.algorithm : '',
      fingerprint: typeof k.fingerprint === 'string' ? k.fingerprint : '',
    }));
}

function parseHost(value: unknown): SshHostView {
  // The list path hands us an already-parsed object; the add path hands us a
  // single JSON string. Accept both so neither re-parses or drops fields.
  let obj: Record<string, unknown> | null = null;
  if (typeof value === 'string') {
    if (value === '') return EMPTY_HOST;
    try {
      const parsed = JSON.parse(value) as unknown;
      obj =
        parsed !== null && typeof parsed === 'object'
          ? (parsed as Record<string, unknown>)
          : null;
    } catch {
      return EMPTY_HOST;
    }
  } else if (value !== null && typeof value === 'object') {
    obj = value as Record<string, unknown>;
  }
  if (obj === null) return EMPTY_HOST;
  return {
    id: typeof obj.id === 'string' ? obj.id : '',
    displayName: typeof obj.displayName === 'string' ? obj.displayName : '',
    host: typeof obj.host === 'string' ? obj.host : '',
    port: typeof obj.port === 'number' ? obj.port : 0,
    username: typeof obj.username === 'string' ? obj.username : '',
    authKind: typeof obj.authKind === 'number' ? obj.authKind : 0,
    hasCredential: obj.hasCredential === true,
    knownKeys: toKeys(obj.knownKeys),
    createdAt: typeof obj.createdAt === 'number' ? obj.createdAt : 0,
    updatedAt: typeof obj.updatedAt === 'number' ? obj.updatedAt : 0,
  };
}

function parseList(json: unknown): SshHostView[] {
  if (typeof json !== 'string' || json === '') return [];
  try {
    const value = JSON.parse(json) as unknown;
    return Array.isArray(value) ? value.map(parseHost) : [];
  } catch {
    return [];
  }
}

export const sshHosts = {
  list(): Promise<SshHostView[]> {
    return NativeSshHost.list().then(
      d => parseList(d.hosts),
      e => {
        throw toError(e);
      },
    );
  },

  add(params: SshHostAddParams): Promise<SshHostView> {
    return NativeSshHost.add({
      displayName: params.displayName,
      host: params.host,
      port: params.port,
      username: params.username,
      useKey: params.useKey,
      password: params.password ?? '',
      privateKey: params.privateKey ?? '',
      passphrase: params.passphrase ?? '',
    }).then(
      d => parseHost(d.host),
      e => {
        throw toError(e);
      },
    );
  },

  /**
   * Tests an endpoint and credential without saving anything.
   *
   * Pass `hostId` when editing so an already-accepted host key is checked.
   */
  testConnection(params: SshHostTestParams): Promise<SshHostTestResult> {
    return NativeSshHost.testConnection({
      ...(params.hostId === undefined ? {} : { hostId: params.hostId }),
      host: params.host,
      port: params.port,
      username: params.username,
      useKey: params.credential.useKey,
      password: params.credential.password ?? '',
      privateKey: params.credential.privateKey ?? '',
      passphrase: params.credential.passphrase ?? '',
    }).then(
      d => ({
        ok: d.ok === true,
        code: typeof d.code === 'string' ? d.code : '',
        stage: typeof d.stage === 'string' ? d.stage : '',
        message: typeof d.message === 'string' ? d.message : '',
        hostKeyAlgorithm:
          typeof d.hostKeyAlgorithm === 'string' ? d.hostKeyAlgorithm : '',
        hostKeyFingerprint:
          typeof d.hostKeyFingerprint === 'string' ? d.hostKeyFingerprint : '',
        hostKeyKnown: d.hostKeyKnown === true,
        hostKeyChanged: d.hostKeyChanged === true,
      }),
      e => {
        throw toError(e);
      },
    );
  },

  update(hostId: string, params: SshHostUpdateParams): Promise<SshHostView> {
    const cred = params.credential;
    return NativeSshHost.update({
      hostId,
      ...(params.displayName === undefined
        ? {}
        : { displayName: params.displayName }),
      ...(params.host === undefined ? {} : { host: params.host }),
      ...(params.port === undefined ? {} : { port: params.port }),
      ...(params.username === undefined ? {} : { username: params.username }),
      ...(params.clearKnownKeys === undefined
        ? {}
        : { clearKnownKeys: params.clearKnownKeys }),
      ...(cred === undefined
        ? {}
        : {
            replaceCredential: true,
            useKey: cred.useKey,
            password: cred.password ?? '',
            privateKey: cred.privateKey ?? '',
            passphrase: cred.passphrase ?? '',
          }),
    }).then(
      d => parseHost(d.host),
      e => {
        throw toError(e);
      },
    );
  },

  setCredential(
    hostId: string,
    params: SshHostCredentialParams,
  ): Promise<void> {
    return toVoid(
      NativeSshHost.setCredential({
        hostId,
        useKey: params.useKey,
        password: params.password ?? '',
        privateKey: params.privateKey ?? '',
        passphrase: params.passphrase ?? '',
      }),
    );
  },

  rename(hostId: string, displayName: string): Promise<void> {
    return toVoid(NativeSshHost.rename(hostId, displayName));
  },

  remove(hostId: string): Promise<void> {
    return toVoid(NativeSshHost.remove(hostId));
  },
};
