import { describe, it, expect } from '@jest/globals';
import type { SftpBridge, SftpRawEntry } from '../files';
import {
  SftpFilesBackend,
  baseName,
  isPlainName,
  parentPath,
  joinPath,
  parseBreadcrumbs,
  SFTP_CAPABILITIES,
} from '../files';

describe('path helpers', () => {
  it('baseName', () => {
    expect(baseName('/home/dev/src')).toBe('src');
    expect(baseName('/')).toBe('/');
    expect(baseName('/home/dev/')).toBe('dev');
    expect(baseName('C:\\Users\\dev')).toBe('dev');
    expect(baseName('C:\\')).toBe('C:\\');
  });

  it('parentPath', () => {
    expect(parentPath('/home/dev/src')).toBe('/home/dev');
    expect(parentPath('/home/dev')).toBe('/home');
    expect(parentPath('/home')).toBe('/');
    expect(parentPath('/')).toBeNull();
    expect(parentPath('C:\\Users\\dev')).toBe('C:\\Users');
    expect(parentPath('C:\\Users')).toBe('C:\\');
    expect(parentPath('C:\\')).toBeNull();
  });

  it('joinPath does not double separators', () => {
    expect(joinPath('/home/dev', 'src')).toBe('/home/dev/src');
    expect(joinPath('/home/dev/', 'src')).toBe('/home/dev/src');
    expect(joinPath('/', 'home')).toBe('/home');
    expect(joinPath('C:\\Users', 'dev')).toBe('C:\\Users\\dev');
    expect(joinPath('C:\\', 'Users')).toBe('C:\\Users');
  });

  /**
   * A typed name is joined onto the current directory, so one carrying a
   * separator or a dot segment acts outside the directory the user is looking
   * at. The prompt rejects those rather than silently nesting or moving.
   */
  it('isPlainName accepts a single entry name', () => {
    expect(isPlainName('notes.txt')).toBe(true);
    expect(isPlainName('  spaced name  ')).toBe(true);
    expect(isPlainName('.hidden')).toBe(true);
    expect(isPlainName('..leading')).toBe(true);
  });

  it('isPlainName rejects anything that escapes the directory', () => {
    expect(isPlainName('a/b')).toBe(false);
    expect(isPlainName('../x')).toBe(false);
    expect(isPlainName('..')).toBe(false);
    expect(isPlainName('.')).toBe(false);
    expect(isPlainName('C:\\Users')).toBe(false);
    expect(isPlainName('')).toBe(false);
    expect(isPlainName('   ')).toBe(false);
    expect(isPlainName('nul\u0000byte')).toBe(false);
  });

  it('parseBreadcrumbs for unix', () => {
    const crumbs = parseBreadcrumbs('/home/dev/src', '/');
    expect(crumbs.map(c => c.path)).toEqual([
      '/',
      '/home',
      '/home/dev',
      '/home/dev/src',
    ]);
    expect(crumbs[0].name).toBe('/');
    expect(crumbs[3].name).toBe('src');
  });

  it('parseBreadcrumbs for windows', () => {
    const crumbs = parseBreadcrumbs('C:\\Users\\dev', 'C:\\');
    expect(crumbs.map(c => c.path)).toEqual([
      'C:\\',
      'C:\\Users',
      'C:\\Users\\dev',
    ]);
    expect(crumbs[1].name).toBe('Users');
  });

  it('parseBreadcrumbs when already at root', () => {
    expect(parseBreadcrumbs('/', '/').map(c => c.path)).toEqual(['/']);
  });
});

describe('capability tables', () => {
  /**
   * SFTP resumes but cannot prove integrity. An upload reopens the remote file
   * and appends; a download seeks past what is already on disk. There is no
   * whole-file hash in the protocol, so proving the result would mean reading
   * the file back over the wire, which costs a second transfer.
   */
  it('sftp claims resume but not whole-file integrity', () => {
    expect(SFTP_CAPABILITIES.transferResume).toBe(true);
    expect(SFTP_CAPABILITIES.transferIntegrity).toBe(false);
    expect(SFTP_CAPABILITIES.list).toBe(true);
  });
});

describe('SftpFilesBackend', () => {
  function sftpEntry(
    name: string,
    isDirectory = false,
    size = 0,
    ms = 0,
  ): SftpRawEntry {
    return {
      name,
      isDirectory,
      isSymlink: false,
      size,
      modifyTimeMillis: ms,
      permissions: 0o644,
    };
  }

  function mockBridge(list: SftpRawEntry[]): {
    bridge: SftpBridge;
    calls: string[];
  } {
    const calls: string[] = [];
    const bridge: SftpBridge = {
      connect: async hostId => {
        calls.push(`connect:${hostId}`);
      },
      status: async () => ({ state: 'READY' }),
      hostKey: async (hostId, accept) => {
        calls.push(`hostKey:${hostId}:${accept}`);
      },
      list: async (hostId, path) => {
        calls.push(`list:${hostId}:${path}`);
        return list;
      },
      stat: async (_h, path) => {
        calls.push(`stat:${path}`);
        return sftpEntry(path, true);
      },
      realPath: async (_h, path) => path,
      mkdir: async (_h, path) => {
        calls.push(`mkdir:${path}`);
      },
      rename: async (_h, from, to) => {
        calls.push(`rename:${from}:${to}`);
      },
      remove: async (_h, path, isDir) => {
        calls.push(`remove:${path}:${isDir}`);
      },
      close: async hostId => {
        calls.push(`close:${hostId}`);
      },
    };
    return { bridge, calls };
  }

  it('maps SFTP entries to the model and converts ms to seconds', async () => {
    const { bridge } = mockBridge([
      sftpEntry('z.txt', false, 10, 1500),
      sftpEntry('adir', true, 0, 0),
      sftpEntry('a.txt', false, 5, 2500),
    ]);
    const backend = new SftpFilesBackend('host-1', bridge);
    expect(backend.capabilities.transferResume).toBe(true);
    expect(backend.capabilities.transferIntegrity).toBe(false);
    const entries = await backend.list('/');
    expect(entries.map(e => e.name).sort()).toEqual(['a.txt', 'adir', 'z.txt']);
    // 1500 ms -> 1 s, 2500 ms -> 2 s.
    expect(entries.find(e => e.name === 'z.txt')?.mtime).toBe(1);
    expect(entries.find(e => e.name === 'a.txt')?.size).toBe(5);
  });

  // The browser used to ask for 500 entries at a time, and each page re-read
  // the whole directory because SFTP readdir has no resumable cursor. Worse,
  // the pages past the first were only fetched by scrolling, so a search that
  // matched inside the first page never looked at the rest of the folder.
  it('returns the whole directory from a single bridge call', async () => {
    const many = Array.from({ length: 1200 }, (_, i) =>
      sftpEntry(`f${i.toString().padStart(4, '0')}.txt`, false, i),
    );
    const { bridge, calls } = mockBridge(many);
    const backend = new SftpFilesBackend('host-1', bridge);
    const entries = await backend.list('/');
    expect(entries).toHaveLength(1200);
    expect(entries[1199].name).toBe('f1199.txt');
    expect(calls.filter(c => c.startsWith('list:'))).toEqual(['list:host-1:/']);
  });

  it('routes metadata ops to the bridge with the host id', async () => {
    const { bridge, calls } = mockBridge([]);
    const backend = new SftpFilesBackend('host-9', bridge);
    await backend.mkdir('/newdir');
    await backend.rename('/a', '/b');
    await backend.remove('/a', 'file');
    await backend.remove('/d', 'dir');
    expect(calls).toContain('mkdir:/newdir');
    expect(calls).toContain('rename:/a:/b');
    expect(calls).toContain('remove:/a:false');
    expect(calls).toContain('remove:/d:true');
  });

  it('roots is the single SFTP root', async () => {
    const { bridge } = mockBridge([]);
    const backend = new SftpFilesBackend('host-1', bridge);
    expect(await backend.roots()).toEqual(['/']);
  });

  it('surfaces a bridge failure as a rejected promise', async () => {
    const bridge: SftpBridge = {
      connect: async () => {},
      status: async () => ({ state: 'READY' }),
      hostKey: async () => {},
      list: async () => {
        throw new Error('no such file');
      },
      stat: async () => ({} as SftpRawEntry),
      realPath: async (_h, path) => path,
      mkdir: async () => {},
      rename: async () => {},
      remove: async () => {},
      close: async () => {},
    };
    const backend = new SftpFilesBackend('host-1', bridge);
    await expect(backend.list('/')).rejects.toThrow('no such file');
  });
});
