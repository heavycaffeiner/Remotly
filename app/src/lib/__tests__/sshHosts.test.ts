import { beforeEach, describe, expect, it, jest } from '@jest/globals';

jest.mock('../../specs/NativeRemotlySshHost', () => ({
  __esModule: true,
  default: {
    list: jest.fn(),
    add: jest.fn(),
    setCredential: jest.fn(),
    rename: jest.fn(),
    remove: jest.fn(),
  },
}));

import NativeSshHost from '../../specs/NativeRemotlySshHost';
import { sshHostDisplayName, sshHosts } from '../sshHosts';

// The native store serializes hosts as a JSON string: list() is a stringified
// array, add() a single stringified object. The regression this guards is a
// double parse that turned every listed host into an empty record (id '', port
// 0), which then opened the terminal/files pages with no host at all.
const HOST = {
  id: 'id-abc',
  displayName: 'My Server',
  host: '192.168.1.10',
  port: 2222,
  username: 'root',
  authKind: 0,
  hasCredential: true,
  knownKeys: [{ algorithm: 'ssh-ed25519', fingerprint: 'SHA256:abc' }],
  createdAt: 1700000000,
  updatedAt: 1700000001,
};

beforeEach(() => {
  jest.clearAllMocks();
});

describe('sshHosts', () => {
  it('list() parses the native array string into full host records', async () => {
    (
      NativeSshHost.list as jest.MockedFunction<typeof NativeSshHost.list>
    ).mockResolvedValue({ hosts: JSON.stringify([HOST]) });
    const list = await sshHosts.list();
    expect(list).toHaveLength(1);
    expect(list[0]).toEqual(HOST);
    expect(list[0].id).toBe('id-abc');
    expect(list[0].port).toBe(2222);
    expect(list[0].host).toBe('192.168.1.10');
  });

  it('list() returns an empty array on a malformed payload', async () => {
    (
      NativeSshHost.list as jest.MockedFunction<typeof NativeSshHost.list>
    ).mockResolvedValue({ hosts: '{not json' });
    expect(await sshHosts.list()).toEqual([]);
  });

  it('add() parses the single host string into a full record', async () => {
    (
      NativeSshHost.add as jest.MockedFunction<typeof NativeSshHost.add>
    ).mockResolvedValue({ host: JSON.stringify(HOST) });
    const host = await sshHosts.add({
      displayName: 'My Server',
      host: '192.168.1.10',
      port: 2222,
      username: 'root',
      useKey: false,
      password: 'pw',
    });
    expect(host.id).toBe('id-abc');
    expect(host.port).toBe(2222);
    expect(host.username).toBe('root');
    expect(host.knownKeys).toHaveLength(1);
  });

  it('list() and add() agree on the parsed shape (no field drift)', async () => {
    (
      NativeSshHost.list as jest.MockedFunction<typeof NativeSshHost.list>
    ).mockResolvedValue({ hosts: JSON.stringify([HOST]) });
    const fromList = (await sshHosts.list())[0];

    (
      NativeSshHost.add as jest.MockedFunction<typeof NativeSshHost.add>
    ).mockResolvedValue({ host: JSON.stringify(HOST) });
    const fromAdd = await sshHosts.add({
      displayName: HOST.displayName,
      host: HOST.host,
      port: HOST.port,
      username: HOST.username,
      useKey: false,
      password: 'pw',
    });

    expect(fromAdd).toEqual(fromList);
  });
});

describe('sshHostDisplayName', () => {
  const host = (
    displayName: string,
    username = 'alice',
    h = 'example.com',
  ) => ({
    displayName,
    username,
    host: h,
  });

  it('uses the label when there is one', () => {
    expect(sshHostDisplayName(host('Prod box'))).toBe('Prod box');
  });

  it('falls back to username@host for an empty label', () => {
    // The label is optional, so the fallback is derived rather than stored.
    // Storing it would tie the label to the endpoint and rewrite it on an edit.
    expect(sshHostDisplayName(host(''))).toBe('alice@example.com');
  });

  it('treats a whitespace-only label as empty', () => {
    expect(sshHostDisplayName(host('   '))).toBe('alice@example.com');
  });

  it('keeps a CJK label exactly as stored', () => {
    expect(sshHostDisplayName(host('개발서버'))).toBe('개발서버');
  });

  it('does not trim inside the label', () => {
    expect(sshHostDisplayName(host(' Prod box '))).toBe('Prod box');
  });
});
