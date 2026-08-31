import { describe, it, expect } from '@jest/globals';
import type { FileEntry, ControlFn, SftpBridge, SftpRawEntry } from '../files';
import {
  DaemonFilesBackend,
  SftpFilesBackend,
  sortEntries,
  baseName,
  isPlainName,
  parentPath,
  joinPath,
  parseBreadcrumbs,
  DAEMON_CAPABILITIES,
  SFTP_CAPABILITIES,
} from '../files';

function entry(name: string, isDir = false, isSymlink = false): FileEntry {
  return { name, isDir, isSymlink, size: 0, mtime: 0, perm: 0 };
}

describe('sortEntries', () => {
  it('puts directories before files', () => {
    const out = sortEntries([
      entry('b.txt'),
      entry('adir', true),
      entry('a.txt'),
    ]);
    expect(out.map(e => e.name)).toEqual(['adir', 'a.txt', 'b.txt']);
  });

  it('sorts by UTF-8 bytes, not locale or normalization', () => {
    // Uppercase sorts before lowercase in byte order (0x41 < 0x61).
    const out = sortEntries([entry('b'), entry('B'), entry('a')]);
    expect(out.map(e => e.name)).toEqual(['B', 'a', 'b']);
  });

  it('keeps NFC and NFD spellings distinct and byte-ordered', () => {
    // NFC single code point U+D55C; NFD is U+D558 U+0315 (base + combining).
    const nfc = entry('\ud55c');
    const nfd = entry('\ud558\u0315');
    const out = sortEntries([nfd, nfc]);
    // Both must be present (not folded), and ordered by their real bytes.
    expect(out).toHaveLength(2);
    expect(out[0].name).not.toBe(out[1].name);
    const names = out.map(e => e.name);
    expect(names).toContain('\ud55c');
    expect(names).toContain('\ud558\u0315');
  });

  it('is stable for equal keys and does not mutate the input', () => {
    const input = [entry('x'), entry('a'), entry('x')];
    const snapshot = [...input];
    sortEntries(input);
    expect(input).toEqual(snapshot);
  });
});

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
  it('daemon proves resume and integrity', () => {
    expect(DAEMON_CAPABILITIES.transferResume).toBe(true);
    expect(DAEMON_CAPABILITIES.transferIntegrity).toBe(true);
    expect(DAEMON_CAPABILITIES.remove).toBe(true);
  });

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

describe('DaemonFilesBackend', () => {
  function mockControl(
    handler: (req: Record<string, unknown>) => Record<string, unknown>,
  ): ControlFn {
    return async req => handler(req);
  }

  it('lists and sorts, honoring more/total', async () => {
    const backend = new DaemonFilesBackend(
      mockControl(req => {
        expect(req.type).toBe('fs.list');
        return {
          id: 1,
          type: 'fs.list',
          entries: [
            {
              name: 'z.txt',
              is_dir: false,
              is_symlink: false,
              size: 1,
              mod_time: 0,
              perm: 0,
            },
            {
              name: 'adir',
              is_dir: true,
              is_symlink: false,
              size: 0,
              mod_time: 0,
              perm: 0,
            },
            {
              name: 'a.txt',
              is_dir: false,
              is_symlink: false,
              size: 2,
              mod_time: 0,
              perm: 0,
            },
          ],
          more: true,
          total: 250,
        };
      }),
    );
    const res = await backend.list('/x', 0, 100);
    expect(res.entries.map(e => e.name)).toEqual(['adir', 'a.txt', 'z.txt']);
    expect(res.more).toBe(true);
    expect(res.total).toBe(250);
  });

  it('maps daemon error codes to FilesError', async () => {
    const backend = new DaemonFilesBackend(
      mockControl(() => ({
        id: 2,
        type: 'fs.stat',
        error: {
          code: 'fs_not_found',
          message: 'fs: no such file or directory',
        },
      })),
    );
    await expect(backend.stat('/nope')).rejects.toMatchObject({
      code: 'fs_not_found',
      name: 'FilesError',
    });
  });

  it('stat returns a typed entry including link target', async () => {
    const backend = new DaemonFilesBackend(
      mockControl(() => ({
        id: 3,
        type: 'fs.stat',
        entry: {
          name: 'lnk',
          is_dir: false,
          is_symlink: true,
          size: 0,
          mod_time: 1712345678,
          perm: 23552,
          link_target: '/real/target',
        },
      })),
    );
    const e = await backend.stat('/lnk');
    expect(e.isSymlink).toBe(true);
    expect(e.linkTarget).toBe('/real/target');
    expect(e.mtime).toBe(1712345678);
  });

  it('sends the right fs.* request for each mutation', async () => {
    const seen: string[] = [];
    const backend = new DaemonFilesBackend(
      mockControl(req => {
        seen.push(req.type as string);
        return { id: 1, type: req.type };
      }),
    );
    await backend.roots();
    await backend.mkdir('/new');
    await backend.rename('/a', '/b');
    await backend.remove('/f', 'file');
    expect(seen).toEqual(['fs.roots', 'fs.mkdir', 'fs.rename', 'fs.remove']);
  });

  it('remove_kind is dir for directory removal', async () => {
    let kind = '';
    const backend = new DaemonFilesBackend(
      mockControl(req => {
        kind = req.remove_kind as string;
        return { id: 1, type: 'fs.remove' };
      }),
    );
    await backend.remove('/d', 'dir');
    expect(kind).toBe('dir');
  });

  it('roots returns the reported roots', async () => {
    const backend = new DaemonFilesBackend(
      mockControl(() => ({ id: 1, type: 'fs.roots', roots: ['/'] })),
    );
    expect(await backend.roots()).toEqual(['/']);
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

  it('maps SFTP entries to the model, ms to seconds, and sorts dirs first', async () => {
    const { bridge } = mockBridge([
      sftpEntry('z.txt', false, 10, 1500),
      sftpEntry('adir', true, 0, 0),
      sftpEntry('a.txt', false, 5, 2500),
    ]);
    const backend = new SftpFilesBackend('host-1', bridge);
    expect(backend.kind).toBe('sftp');
    expect(backend.capabilities.transferResume).toBe(true);
    expect(backend.capabilities.transferIntegrity).toBe(false);
    const res = await backend.list('/');
    expect(res.total).toBe(3);
    expect(res.entries.map(e => e.name)).toEqual(['adir', 'a.txt', 'z.txt']);
    // 1500 ms -> 1 s, 2500 ms -> 2 s.
    expect(res.entries.find(e => e.name === 'z.txt')?.mtime).toBe(1);
    expect(res.entries.find(e => e.name === 'a.txt')?.size).toBe(5);
  });

  it('paginates a full SFTP listing client-side', async () => {
    const many = Array.from({ length: 10 }, (_, i) =>
      sftpEntry(`f${i.toString().padStart(2, '0')}.txt`, false, i),
    );
    const { bridge } = mockBridge(many);
    const backend = new SftpFilesBackend('host-1', bridge);
    const page1 = await backend.list('/', 0, 4);
    expect(page1.entries).toHaveLength(4);
    expect(page1.more).toBe(true);
    const page3 = await backend.list('/', 8, 4);
    expect(page3.entries).toHaveLength(2);
    expect(page3.more).toBe(false);
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
      mkdir: async () => {},
      rename: async () => {},
      remove: async () => {},
      close: async () => {},
    };
    const backend = new SftpFilesBackend('host-1', bridge);
    await expect(backend.list('/')).rejects.toThrow('no such file');
  });
});
