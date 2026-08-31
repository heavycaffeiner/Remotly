// Backend-agnostic file browser model for the Remotly files browser (M4-06).
//
// One logical contract over two backends: the daemon filesystem (fs.* control
// plus resumable transfers) and SSH SFTP (via the SshBridge). The model keeps
// backend capability differences explicit rather than pretending they are
// identical: the daemon proves offset resume and whole-file integrity, while
// SSH SFTP offers only a from-start retry and no whole-file hash.
//
// Names are treated as untrusted, byte-faithful identifiers. Nothing here
// normalizes NFC/NFD, folds case, or derives a local path from a remote name.
// Display sorting is deterministic and independent of normalization; the raw
// name is always the operation identifier.

export interface FileEntry {
  /** Raw basename, byte-faithful. Never normalize this. */
  name: string;
  isDir: boolean;
  isSymlink: boolean;
  /** Bytes. 0 when the object reports none (symlink via lstat, most dirs). */
  size: number;
  /** Unix seconds. 0 when unknown. */
  mtime: number;
  /** Full POSIX mode bits. 0 when the backend has no portable perm. */
  perm: number;
  /** Best-effort readlink target, for display only. Absent for non-links. */
  linkTarget?: string;
}

export interface ListResult {
  entries: FileEntry[];
  more: boolean;
  total: number;
}

/** What a backend can do. The UI reads this to show or hide affordances and
 *  to label resume/integrity honestly. */
export interface FilesCapabilities {
  list: boolean;
  stat: boolean;
  mkdir: boolean;
  rename: boolean;
  remove: boolean;
  upload: boolean;
  download: boolean;
  /** Offset resume for an interrupted transfer. Daemon: proven. SSH: not
   *  proven by the library and server, so false. */
  transferResume: boolean;
  /** Whole-file integrity check on completion. Daemon: SHA-256. SSH SFTP:
   *  no whole-file hash, so false. */
  transferIntegrity: boolean;
}

/** Daemon proves both resume and integrity; every metadata op is available. */
export const DAEMON_CAPABILITIES: FilesCapabilities = {
  list: true,
  stat: true,
  mkdir: true,
  rename: true,
  remove: true,
  upload: true,
  download: true,
  transferResume: true,
  transferIntegrity: true,
};

/** SSH SFTP exposes metadata and chunked transfer, but not proven offset
 *  resume or a whole-file hash. The UI must label SSH resume accordingly. */
export const SFTP_CAPABILITIES: FilesCapabilities = {
  list: true,
  stat: true,
  mkdir: true,
  rename: true,
  remove: true,
  upload: true,
  download: true,
  // Resume is implemented: an upload reopens the remote file and appends,
  // and a download seeks past what is already on disk. Integrity is not, and
  // cannot be: the SFTP protocol carries no whole-file hash, so proving one
  // would mean transferring the file a second time to read it back.
  transferResume: true,
  transferIntegrity: false,
};

export type RemoveKind = 'file' | 'dir';

/** A typed browser error. `code` is the backend's stable code (e.g.
 *  "fs_not_found"); the UI matches on code, never on the message. */
export class FilesError extends Error {
  code: string;
  constructor(code: string, message: string) {
    super(message);
    this.name = 'FilesError';
    this.code = code;
  }
}

/** The logical browser contract. Both backends implement it; the capability
 *  table records where the guarantees differ. */
export interface FilesBackend {
  readonly kind: 'daemon' | 'sftp';
  readonly capabilities: FilesCapabilities;
  roots(): Promise<string[]>;
  list(path: string, offset?: number, limit?: number): Promise<ListResult>;
  stat(path: string): Promise<FileEntry>;
  mkdir(path: string): Promise<void>;
  rename(from: string, to: string): Promise<void>;
  remove(path: string, kind: RemoveKind): Promise<void>;
}

// --- deterministic, normalization-free display sorting ---------------------

// UTF-8 byte-wise comparison of two raw names. It is locale-independent and
// matches the daemon's own listing order (os.ReadDir sorts by byte name), so
// the client and server agree. It deliberately does not use localeCompare or
// any normalized form, so NFC and NFD spellings sort by their real bytes and
// remain distinct.
function compareNames(a: string, b: string): number {
  const ab = utf8(a);
  const bb = utf8(b);
  const n = Math.min(ab.length, bb.length);
  for (let i = 0; i < n; i++) {
    if (ab[i] !== bb[i]) return ab[i] - bb[i];
  }
  return ab.length - bb.length;
}

// UTF-8 encoder, hand-rolled because Hermes (and the RN tsconfig, which omits
// the dom lib) provides no TextEncoder. Output is byte-identical to
// TextEncoder#encode: BMP code points plus astral via surrogate pairs.
/* eslint-disable no-bitwise -- utf-8 is inherently bitwise */
function utf8(s: string): Uint8Array {
  const out: number[] = [];
  for (let i = 0; i < s.length; i++) {
    let cp = s.charCodeAt(i);
    if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < s.length) {
      const lo = s.charCodeAt(i + 1);
      if (lo >= 0xdc00 && lo <= 0xdfff) {
        cp = 0x10000 + ((cp - 0xd800) << 10) + (lo - 0xdc00);
        i++;
      }
    }
    if (cp < 0x80) {
      out.push(cp);
    } else if (cp < 0x800) {
      out.push(0xc0 | (cp >> 6), 0x80 | (cp & 0x3f));
    } else if (cp < 0x10000) {
      out.push(
        0xe0 | (cp >> 12),
        0x80 | ((cp >> 6) & 0x3f),
        0x80 | (cp & 0x3f),
      );
    } else {
      out.push(
        0xf0 | (cp >> 18),
        0x80 | ((cp >> 12) & 0x3f),
        0x80 | ((cp >> 6) & 0x3f),
        0x80 | (cp & 0x3f),
      );
    }
  }
  return Uint8Array.from(out);
}
/* eslint-enable no-bitwise */

/** Directories first, then byte-wise by raw name. Returns a new array; the
 *  input order of equal entries is preserved (stable). */
export function sortEntries(entries: readonly FileEntry[]): FileEntry[] {
  return [...entries].sort((a, b) => {
    if (a.isDir !== b.isDir) return a.isDir ? -1 : 1;
    return compareNames(a.name, b.name);
  });
}

// --- path helpers (Unix and Windows forms) --------------------------------

// Detects the separator from the path itself. Windows paths contain backslashes
// (and may start with a drive or UNC); everything else uses the forward slash.
function sepOf(p: string): string {
  return p.includes('\\') ? '\\' : '/';
}

function isRoot(p: string): boolean {
  if (p === '') return false;
  if (p === '/') return true;
  // A bare drive (C:) or a drive root (C:\) is a Windows root.
  if (/^[a-zA-Z]:$/.test(p) || /^[a-zA-Z]:\\?$/.test(p)) return true;
  // A UNC root (\\server\share).
  if (/^\\\\[^\\/]+\\[^\\/]+$/.test(p)) return true;
  return false;
}

/** The last path segment, with trailing separators ignored. For a root it is
 *  the root itself. */
export function baseName(p: string): string {
  const sep = sepOf(p);
  if (isRoot(p)) return p.replace(new RegExp(`[${escapeRe(sep)}]+$`), '') + sep;
  const trimmed = p.replace(new RegExp(`[${escapeRe(sep)}]+$`), '');
  const parts = trimmed.split(sep).filter(x => x !== '');
  return parts[parts.length - 1] ?? trimmed;
}

/** The parent directory, or null when p is a root (nothing above it). */
export function parentPath(p: string): string | null {
  const sep = sepOf(p);
  const trimmed = p.replace(new RegExp(`[${escapeRe(sep)}]+$`), '');
  if (trimmed === '' || isRoot(trimmed)) return null;
  const idx = trimmed.lastIndexOf(sep);
  if (idx < 0) return null;
  let parent = trimmed.slice(0, idx);
  if (parent === '') return sep === '/' ? '/' : null;
  // A bare drive (C:) is really the drive root (C:\).
  if (sep === '\\' && /^[a-zA-Z]:$/.test(parent)) parent = parent + sep;
  return parent;
}

/** Joins a directory and a child name into a native path. Does not double the
 *  separator at a root. */
export function joinPath(dir: string, name: string): string {
  const sep = sepOf(dir) === '\\' ? '\\' : '/';
  const d = dir.replace(new RegExp(`[${escapeRe(sep)}]+$`), '');
  if (d === '') return sep === '/' ? '/' + name : name;
  if (isRoot(d) || /^[a-zA-Z]:$/.test(d)) {
    // A root already ends with its separator (or is a bare drive root).
    return d.endsWith(sep) ? d + name : d + sep + name;
  }
  return d + sep + name;
}

/**
 * True when a user-typed name is a single entry in its directory.
 *
 * The new-folder and rename fields are joined onto the current directory, so a
 * name carrying a separator or a dot segment silently writes somewhere else:
 * "a/b" creates a nested folder, "../x" moves the entry out of the directory
 * the user is looking at, and neither reports what it did. Names are checked
 * against both separators regardless of the host, because the name is typed
 * before the remote's own convention is known.
 */
export function isPlainName(name: string): boolean {
  const trimmed = name.trim();
  if (trimmed === '' || trimmed === '.' || trimmed === '..') return false;
  if (trimmed.includes('/') || trimmed.includes('\\')) return false;
  // NUL cannot appear in a path on any host this talks to.
  return !trimmed.includes('\0');
}

export interface Breadcrumb {
  path: string;
  name: string;
}

/** The breadcrumb chain from the root down to path, inclusive. Each entry's
 *  path is the directory to navigate to, and name its label. Deterministic and
 *  separator-aware. */
export function parseBreadcrumbs(path: string, root: string): Breadcrumb[] {
  const sep = sepOf(path) === '\\' ? '\\' : '/';
  const strip = (s: string) =>
    s.replace(new RegExp(`[${escapeRe(sep)}]+$`), '');
  const rootTrimmed = strip(root);
  const curTrimmed = strip(path);

  const rootPath = rootTrimmed === '' ? sep : rootTrimmed + sep;
  const rootName = rootTrimmed === '' ? '/' : rootTrimmed + sep;
  const crumbs: Breadcrumb[] = [{ path: rootPath, name: rootName }];

  if (curTrimmed === rootTrimmed || curTrimmed === '') {
    return crumbs;
  }
  const rel = curTrimmed
    .slice(rootTrimmed.length)
    .replace(new RegExp(`^[${escapeRe(sep)}]+`), '');
  const parts = rel.split(sep).filter(x => x !== '');
  let acc = rootPath;
  for (const part of parts) {
    acc = acc.endsWith(sep) ? acc + part : acc + sep + part;
    crumbs.push({ path: acc, name: part });
  }
  return crumbs;
}

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

// --- daemon backend --------------------------------------------------------

// A minimal control channel: send one control request, get the parsed
// response back. The transport's control() fits this shape. The daemon answers
// errors as { id, type, error: { code, message } }.
export type ControlFn = (
  request: Record<string, unknown>,
) => Promise<Record<string, unknown>>;

const FS_MAX_PAGE = 500;

// Maps a daemon filesystem error code to a FilesError. Unknown codes pass
// through so the UI can still show a typed (if generic) error.
function fsError(code: string, message: string): FilesError {
  return new FilesError(code, message);
}

function checkError(resp: Record<string, unknown>): void {
  const err = resp.error as { code?: string; message?: string } | undefined;
  if (err && typeof err.code === 'string') {
    throw fsError(err.code, err.message ?? err.code);
  }
}

function toEntry(raw: Record<string, unknown>): FileEntry {
  return {
    name: typeof raw.name === 'string' ? raw.name : '',
    isDir: raw.is_dir === true,
    isSymlink: raw.is_symlink === true,
    size: typeof raw.size === 'number' ? raw.size : 0,
    mtime: typeof raw.mod_time === 'number' ? raw.mod_time : 0,
    perm: typeof raw.perm === 'number' ? raw.perm : 0,
    linkTarget:
      typeof raw.link_target === 'string' && raw.link_target !== ''
        ? raw.link_target
        : undefined,
  };
}

// --- SFTP backend ----------------------------------------------------------

// One SFTP entry as the native bridge serializes it (GSON of SftpEntry). Field
// names differ from the daemon wire shape, so this maps to FileEntry separately.
export interface SftpRawEntry {
  name: string;
  isDirectory: boolean;
  isSymlink: boolean;
  size: number;
  modifyTimeMillis: number;
  permissions: number;
}

export interface SftpHostKey {
  host: string;
  port: number;
  algorithm: string;
  fingerprint: string;
}

// The native SFTP bridge, injected for testability. The real implementation
// wraps the RemotlySftp TurboModule (sftp.ts); tests supply a mock. All methods
// operate on a host already stored in the SSH host store; the connect and
// host-key flow are driven by the caller before metadata is used.
export interface SftpBridge {
  connect(hostId: string): Promise<void>;
  status(hostId: string): Promise<{
    state: string;
    hostKey?: SftpHostKey;
    changed?: boolean;
    code?: string;
    message?: string;
  }>;
  hostKey(hostId: string, accept: boolean): Promise<void>;
  list(hostId: string, path: string): Promise<SftpRawEntry[]>;
  stat(hostId: string, path: string): Promise<SftpRawEntry>;
  mkdir(hostId: string, path: string): Promise<void>;
  rename(hostId: string, from: string, to: string): Promise<void>;
  remove(hostId: string, path: string, isDir: boolean): Promise<void>;
  close(hostId: string): Promise<void>;
}

function sftpToEntry(raw: SftpRawEntry): FileEntry {
  return {
    name: raw.name,
    isDir: raw.isDirectory === true,
    isSymlink: raw.isSymlink === true,
    size: typeof raw.size === 'number' ? raw.size : 0,
    // SFTP reports milliseconds; the model uses seconds.
    mtime:
      typeof raw.modifyTimeMillis === 'number'
        ? Math.floor(raw.modifyTimeMillis / 1000)
        : 0,
    perm: typeof raw.permissions === 'number' ? raw.permissions : 0,
  };
}

// The SSH SFTP backend. It assumes a ready session for the host (the browser
// drives connect and the host-key prompt); every op throws FilesError if the
// session is not ready or the server rejects the operation. Listing is
// client-side paginated because SFTP readDir returns the whole directory.
export class SftpFilesBackend implements FilesBackend {
  readonly kind = 'sftp' as const;
  readonly capabilities = SFTP_CAPABILITIES;
  constructor(private hostId: string, private bridge: SftpBridge) {}

  async roots(): Promise<string[]> {
    return ['/'];
  }

  async list(
    path: string,
    offset = 0,
    limit = FS_MAX_PAGE,
  ): Promise<ListResult> {
    const all = sortEntries(
      (await this.bridge.list(this.hostId, path)).map(sftpToEntry),
    );
    const n = Math.max(1, Math.min(limit, FS_MAX_PAGE));
    const start = Math.max(0, offset);
    return {
      entries: all.slice(start, start + n),
      more: start + n < all.length,
      total: all.length,
    };
  }

  async stat(path: string): Promise<FileEntry> {
    return sftpToEntry(await this.bridge.stat(this.hostId, path));
  }

  async mkdir(path: string): Promise<void> {
    await this.bridge.mkdir(this.hostId, path);
  }

  async rename(from: string, to: string): Promise<void> {
    await this.bridge.rename(this.hostId, from, to);
  }

  async remove(path: string, kind: RemoveKind): Promise<void> {
    await this.bridge.remove(this.hostId, path, kind === 'dir');
  }
}

// --- transfer contract -----------------------------------------------------

export type ConflictPolicy = 'fail' | 'replace';
export type TransferDirection = 'upload' | 'download';

export interface TransferHandle {
  id: string;
  direction: TransferDirection;
  path: string;
  size: number;
  /**
   * Where the transfer actually starts, in bytes.
   *
   * Absent or zero means from the beginning. A resumed upload can start
   * somewhere else than the caller asked for, because the backend decides how
   * much of the file the far end already holds, so the caller reads its local
   * file from here rather than from what it requested.
   */
  startOffset?: number;
}

export interface TransferStatus {
  state: 'active' | 'done' | 'error';
  received: number;
  total: number;
  error?: string;
}

// The resumable-transfer contract. The daemon implements offset resume and a
// whole-file SHA-256 (via a file channel); SSH SFTP implements a from-start
// retry with no whole-file hash. The UI reads capabilities to offer resume only
// where the backend proves it.
//
// Uploads are app-driven: the caller writes chunks in order and completes.
// Downloads are push-driven: the backend (the daemon pumps over the file
// channel) delivers chunks to onChunk and settles with onDone or onError. The
// caller decides the sink (for example a content URI); this contract only moves
// bytes, so it is testable without a device.
export interface TransferBackend {
  readonly kind: 'daemon' | 'sftp';
  readonly capabilities: FilesCapabilities;
  startUpload(
    path: string,
    size: number,
    conflict: ConflictPolicy,
    hash?: string,
    /**
     * Byte offset to continue from, for a transfer being picked back up.
     *
     * Zero or absent starts a fresh transfer. A backend that cannot resume
     * ignores it and starts over, which is why the UI only offers Resume where
     * capabilities.transferResume is set.
     */
    resumeFrom?: number,
  ): Promise<TransferHandle>;
  writeChunk(id: string, offset: number, data: Uint8Array): Promise<number>;
  completeUpload(id: string): Promise<void>;
  startDownload(
    path: string,
    onChunk: (offset: number, bytes: Uint8Array) => void,
    onDone: (totalBytes: number) => void,
    onError: (message: string) => void,
  ): Promise<TransferHandle>;
  /**
   * Streams a download into a destination the backend writes itself.
   *
   * Present only where the backend can reach the sink without handing bytes
   * to the caller. It exists because moving file data through JS costs a
   * base64 encode, a bridge crossing, and a JS turn per chunk, all of which
   * scale with file size. `onProgress` reports bytes written so far.
   *
   * Callers must fall back to [startDownload] when this is absent.
   */
  startDownloadToUri?(
    path: string,
    uri: string,
    onProgress: (received: number) => void,
    onDone: (totalBytes: number) => void,
    onError: (message: string) => void,
    resumeFrom?: number,
  ): Promise<TransferHandle>;
  status(id: string): Promise<TransferStatus>;
  cancel(id: string): Promise<void>;
}

/** The daemon filesystem backend, driven entirely by fs.* control requests.
 *  Transfers are a separate concern (file channel) and are not modeled here. */
export class DaemonFilesBackend implements FilesBackend {
  readonly kind = 'daemon' as const;
  readonly capabilities = DAEMON_CAPABILITIES;
  private control: ControlFn;

  constructor(control: ControlFn) {
    this.control = control;
  }

  async roots(): Promise<string[]> {
    const resp = await this.control({ type: 'fs.roots' });
    checkError(resp);
    const roots = resp.roots;
    return Array.isArray(roots) ? (roots as string[]) : [];
  }

  async list(
    path: string,
    offset = 0,
    limit = FS_MAX_PAGE,
  ): Promise<ListResult> {
    const resp = await this.control({
      type: 'fs.list',
      path,
      offset,
      limit: Math.max(1, Math.min(limit, FS_MAX_PAGE)),
    });
    checkError(resp);
    const raw = Array.isArray(resp.entries)
      ? (resp.entries as Record<string, unknown>[])
      : [];
    return {
      entries: sortEntries(raw.map(toEntry)),
      more: resp.more === true,
      total: typeof resp.total === 'number' ? resp.total : raw.length,
    };
  }

  async stat(path: string): Promise<FileEntry> {
    const resp = await this.control({ type: 'fs.stat', path });
    checkError(resp);
    const e = resp.entry as Record<string, unknown> | undefined;
    if (!e) throw fsError('fs_invalid_path', 'stat returned no entry');
    return toEntry(e);
  }

  async mkdir(path: string): Promise<void> {
    checkError(await this.control({ type: 'fs.mkdir', path }));
  }

  async rename(from: string, to: string): Promise<void> {
    checkError(await this.control({ type: 'fs.rename', from, to }));
  }

  async remove(path: string, kind: RemoveKind): Promise<void> {
    checkError(
      await this.control({ type: 'fs.remove', path, remove_kind: kind }),
    );
  }
}
