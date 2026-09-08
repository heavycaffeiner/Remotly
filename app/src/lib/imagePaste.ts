// Pasting a picked image into an SSH session.
//
// A terminal carries text, so an image cannot be typed into one. Agents that
// accept images read them from disk, so the image is uploaded to the remote
// host over SFTP and its path is typed instead. That makes the paste work for
// anything that takes a filename, not only for one agent.

import type { TransferBackend } from './files';
import { shellQuote } from './shell';

/** Upload chunk size. Matches the browser's own uploads. */
const CHUNK = 64 * 1024;

/** Refuses anything larger, so one paste cannot stall a session. */
export const MAX_IMAGE_BYTES = 12 * 1024 * 1024;

/** Where pasted images are written, relative to the remote home directory. */
export const PASTE_DIR = '.remotly';

/**
 * Names the file for a pasted image.
 *
 * The timestamp keeps two pastes in one session apart. The extension follows
 * the picked file so the remote name matches the bytes actually written;
 * anything unexpected becomes png rather than being trusted, since the name
 * comes from a content provider and is not ours.
 */
export function pastedImageName(now: number, sourceName = ''): string {
  const stamp = new Date(now)
    .toISOString()
    .replace(/[:.]/g, '-')
    .replace('T', '_')
    .replace('Z', '');
  return `paste_${stamp}.${imageExtension(sourceName)}`;
}

/** The extension to write, taken from a picked file name. */
export function imageExtension(sourceName: string): string {
  const dot = sourceName.lastIndexOf('.');
  if (dot < 0 || dot === sourceName.length - 1) return 'png';
  const ext = sourceName.slice(dot + 1).toLowerCase();
  return /^[a-z0-9]{1,5}$/.test(ext) ? ext : 'png';
}

/**
 * Joins a remote directory and a name using the server's own separator.
 *
 * The home directory is whatever the server reported, so a Windows OpenSSH
 * host can answer with a backslash path. Guessing a separator either puts the
 * file somewhere the shell cannot find it or creates a name with a slash in it.
 */
export function joinRemote(dir: string, name: string): string {
  const backslash = dir.includes('\\') && !dir.includes('/');
  const sep = backslash ? '\\' : '/';
  return `${dir.replace(/[/\\]+$/, '')}${sep}${name}`;
}

export interface ImagePasteResult {
  /** Absolute remote path the image was written to. */
  path: string;
  /** What to type into the session, already quoted. */
  text: string;
}

/** Reads one chunk of the picked image, as the local picker exposes it. */
export type ChunkReader = (
  offset: number,
  maxBytes: number,
) => Promise<{ data: Uint8Array; bytesRead: number }>;

/**
 * Creates the paste directory, tolerating one that already exists.
 *
 * An existing directory reports as an error with no portable way to tell it
 * from a real failure, so the result is ignored here and judged by the upload
 * that follows: that fails loudly if the directory genuinely is not there.
 */
async function ensureDir(
  mkdir: (path: string) => Promise<void>,
  home: string,
): Promise<string> {
  const path = joinRemote(home, PASTE_DIR);
  await mkdir(path).catch(() => undefined);
  return path;
}

/**
 * Uploads a picked image to the remote host and returns what to type.
 *
 * [home] is the remote home directory as the server itself resolved it, so the
 * file lands somewhere readable on Linux, macOS, and Windows alike. Throws when
 * the image is too large or the upload fails, so the caller reports that rather
 * than typing a path to a file that is not there.
 */
export async function pasteImage(
  source: { name: string; size: number },
  read: ChunkReader,
  transfers: TransferBackend,
  mkdir: (path: string) => Promise<void>,
  home: string,
  now: number = Date.now(),
): Promise<ImagePasteResult> {
  if (source.size <= 0) throw new Error('That image is empty.');
  if (source.size > MAX_IMAGE_BYTES) {
    throw new Error('That image is too large to paste.');
  }

  const dir = await ensureDir(mkdir, home);
  const path = joinRemote(dir, pastedImageName(now, source.name));

  const handle = await transfers.startUpload(path, source.size, 'replace');
  try {
    let offset = 0;
    while (offset < source.size) {
      const { data, bytesRead } = await read(
        offset,
        Math.min(CHUNK, source.size - offset),
      );
      if (bytesRead === 0) break;
      await transfers.writeChunk(handle.id, offset, data);
      offset += bytesRead;
    }
    if (offset < source.size) {
      throw new Error('The image could not be read in full.');
    }
    await transfers.completeUpload(handle.id);
  } catch (e) {
    // A truncated file on the remote is worse than none, since an agent would
    // read it as a corrupt image. Cancelling is best effort: the failure that
    // reached here may be the connection itself.
    await transfers.cancel(handle.id).catch(() => undefined);
    throw e;
  }

  // A trailing space so what the user types next does not run into the path.
  return { path, text: `${shellQuote(path)} ` };
}
