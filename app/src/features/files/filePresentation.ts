// Pure presentation logic for the file browser.
//
// Names are byte-faithful throughout: nothing here case-folds, normalizes
// NFC to NFD, or trims. Two entries that differ only by Unicode normalization
// are different files on the server, and collapsing them in the UI would send
// an operation to the wrong one.

import type { FileEntry } from '../../lib/files';
import type { IconName } from '../../components/ui/icon';

/** A stable list key. */
export function entryKey(dir: string, entry: FileEntry): string {
  // The full path, exactly as stored. Using the bare name would collide
  // between directories during a navigation, and normalizing would merge two
  // genuinely distinct entries.
  return `${dir}\u0000${entry.name}`;
}

export function entryIcon(entry: FileEntry): IconName {
  if (entry.isSymlink) return 'link-off';
  if (entry.isDir) return 'folder';
  return 'file';
}

export function formatSize(n: number): string {
  if (!Number.isFinite(n) || n < 0) return '';
  if (n < 1024) return `${n} B`;
  const units = ['KiB', 'MiB', 'GiB', 'TiB'];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i += 1;
  }
  return `${v.toFixed(v >= 100 ? 0 : 1)} ${units[i]}`;
}

export function formatMtime(sec: number): string {
  if (sec <= 0) return '';
  return new Date(sec * 1000).toLocaleString();
}

export function entryDescription(entry: FileEntry): string {
  const parts: string[] = [];
  if (!entry.isDir) {
    const size = formatSize(entry.size);
    if (size !== '') parts.push(size);
  }
  const when = formatMtime(entry.mtime);
  if (when !== '') parts.push(when);
  return parts.join('  ');
}

/** A screen-reader label that states what the row is, not just its name. */
export function entryAccessibilityLabel(entry: FileEntry): string {
  const kind = entry.isSymlink ? 'link' : entry.isDir ? 'folder' : 'file';
  const detail = entryDescription(entry);
  return detail === ''
    ? `${kind} ${entry.name}`
    : `${kind} ${entry.name}, ${detail}`;
}

/** Why a proposed name cannot be used, or null when it is acceptable. */
export function validateName(name: string): string | null {
  if (name === '') return 'Enter a name.';
  if (name === '.' || name === '..') return 'That name is reserved.';
  if (name.includes('/') || name.includes('\\')) {
    return 'A name cannot contain a path separator.';
  }
  // A NUL terminates the string in most server-side APIs, so a name carrying
  // one would address a different file than the one displayed.
  if (name.includes('\u0000'))
    return 'That name contains an invalid character.';
  for (const c of name) {
    if (c.codePointAt(0)! < 0x20) {
      return 'That name contains a control character.';
    }
  }
  if (name.length > 255) return 'That name is too long.';
  return null;
}

/** True when a rename would collide with an entry already in the directory. */
export function nameExists(
  entries: readonly FileEntry[],
  name: string,
): boolean {
  // Exact comparison. A server that distinguishes two normalization forms has
  // two files, and treating them as one would overwrite the wrong one.
  return entries.some(e => e.name === name);
}

export type TransferDirection = 'upload' | 'download';
export type TransferState =
  | 'queued'
  | 'running'
  | 'done'
  | 'failed'
  | 'cancelled'
  | 'conflict';

export interface Transfer {
  id: string;
  direction: TransferDirection;
  /** The name shown to the user. Not a local path. */
  displayName: string;
  remotePath: string;
  transferred: number;
  total: number;
  state: TransferState;
  /** True when this backend can resume an interrupted transfer. */
  resumable: boolean;
  error?: string;
}

/** Progress as a fraction, or null when the total is unknown. */
export function transferProgress(t: Transfer): number | null {
  if (t.total <= 0) return null;
  return Math.min(1, Math.max(0, t.transferred / t.total));
}

export function transferLabel(t: Transfer): string {
  const verb = t.direction === 'upload' ? 'Uploading' : 'Downloading';
  switch (t.state) {
    case 'queued':
      return `Waiting to ${t.direction}`;
    case 'running':
      return `${verb} ${t.displayName}`;
    case 'done':
      return `${t.direction === 'upload' ? 'Uploaded' : 'Downloaded'} ${
        t.displayName
      }`;
    case 'failed':
      return `${t.displayName} failed`;
    case 'cancelled':
      return `${t.displayName} cancelled`;
    case 'conflict':
      return `${t.displayName} already exists`;
    default:
      return t.displayName;
  }
}

/** How a name conflict is resolved. Overwrite is never the default. */
export type ConflictChoice = 'cancel' | 'rename' | 'overwrite';

/**
 * A non-colliding name, by inserting a counter before the extension.
 *
 * `report.txt` becomes `report (1).txt`, matching what a desktop file manager
 * does, so the result is recognizable rather than a timestamped surprise.
 */
/**
 * Splits a name into the stem and extension a counter is inserted between.
 *
 * A leading dot is a hidden file, not an extension, so `.bashrc` keeps its
 * whole name as the stem and becomes `.bashrc (1)`.
 */
export function splitName(name: string): { stem: string; ext: string } {
  const dot = name.lastIndexOf('.');
  if (dot <= 0) return { stem: name, ext: '' };
  return { stem: name.slice(0, dot), ext: name.slice(dot) };
}

/** The nth candidate name, matching what a desktop file manager produces. */
export function numberedName(name: string, n: number): string {
  const { stem, ext } = splitName(name);
  return `${stem} (${n})${ext}`;
}

export function uniqueName(
  entries: readonly FileEntry[],
  name: string,
): string {
  if (!nameExists(entries, name)) return name;
  for (let i = 1; i < 1000; i += 1) {
    const candidate = numberedName(name, i);
    if (!nameExists(entries, candidate)) return candidate;
  }
  const { stem, ext } = splitName(name);
  return `${stem} (${Date.now()})${ext}`;
}
