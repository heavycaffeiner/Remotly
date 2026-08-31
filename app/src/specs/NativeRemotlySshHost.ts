import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

// Spec for the SSH host store TurboModule. Records travel as one JSON string
// so the bridge carries no nested object models; the credential crosses in
// plaintext and is sealed natively, never persisted in the record or logged.
//
// Hosts and their sealed credentials live in one document that is replaced
// atomically, so a failed update leaves the previous host and credential
// usable.

export interface SshHostAddParams {
  /** May be empty. The UI falls back to username@host for display. */
  displayName: string;
  host: string;
  port: number;
  username: string;
  /** true for key auth, false for password auth. */
  useKey?: boolean;
  /** Password auth only. */
  password?: string;
  /** Key auth only: base64 of the PEM-encoded private key. */
  privateKey?: string;
  /** Key auth only: the key decrypt passphrase, when the key is encrypted. */
  passphrase?: string;
}

/**
 * A partial update. An omitted field is left unchanged; an empty string is a
 * real value and clears the field.
 *
 * Changing `host` or `port` produces a new host id and clears the accepted
 * host keys, because trust was established for the previous endpoint.
 */
export interface SshHostUpdateParams {
  hostId: string;
  displayName?: string;
  host?: string;
  port?: number;
  username?: string;
  /** Only when true are the credential fields read. */
  replaceCredential?: boolean;
  useKey?: boolean;
  password?: string;
  privateKey?: string;
  passphrase?: string;
  /** Drops the accepted host keys. Requires explicit user confirmation. */
  clearKnownKeys?: boolean;
}

export interface SshHostSetCredentialParams {
  hostId: string;
  useKey?: boolean;
  password?: string;
  privateKey?: string;
  passphrase?: string;
}

export interface SshHostAddResult {
  /** The stored host record as a JSON string (no credential bytes). */
  host: string;
}

export interface SshHostListResult {
  /** JSON array string of the stored host records. */
  hosts: string;
}

export interface SshHostTestParams {
  /** Supply when editing, so an accepted host key is checked too. */
  hostId?: string;
  host: string;
  port: number;
  username: string;
  useKey?: boolean;
  password?: string;
  privateKey?: string;
  passphrase?: string;
}

export interface SshHostTestResult {
  ok: boolean;
  /** A stable SshCode when ok is false. */
  code: string;
  /** The operation that failed. */
  stage: string;
  /** Short and safe to display. Never a server banner. */
  message: string;
  hostKeyAlgorithm: string;
  hostKeyFingerprint: string;
  /** True when this key is already accepted for the host being edited. */
  hostKeyKnown: boolean;
  /** True when a different key is pinned. The test fails closed. */
  hostKeyChanged: boolean;
}

export interface Spec extends TurboModule {
  /** Lists the stored SSH hosts as a JSON array string. */
  list(): Promise<SshHostListResult>;

  /**
   * Adds a host with its credential. useKey selects key (privateKey) versus
   * password auth. Resolves with the stored record as a JSON string.
   */
  add(params: SshHostAddParams): Promise<SshHostAddResult>;

  /**
   * Applies a patch atomically. Resolves with the stored record, whose id
   * changes when the endpoint changed, so the caller navigates using it.
   */
  update(params: SshHostUpdateParams): Promise<SshHostAddResult>;

  /** Replaces the stored credential for an existing host. */
  setCredential(params: SshHostSetCredentialParams): Promise<void>;

  /** Renames a host's display name. */
  rename(hostId: string, displayName: string): Promise<void>;

  /**
   * Tests an endpoint and credential without saving anything.
   *
   * Persists nothing: no host, no credential, and no pinned host key. A key
   * that contradicts one already accepted fails closed.
   */
  testConnection(params: SshHostTestParams): Promise<SshHostTestResult>;

  /** Removes a host and its stored credential in one replacement. */
  remove(hostId: string): Promise<void>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RemotlySshHost');
