// Pasting a clipboard image into a daemon session.
//
// A terminal carries text, so an image cannot be typed into one. Agents that
// accept images read them from disk, so the image is written to the remote
// host and its path is typed instead. That makes the paste work for anything
// that takes a filename, not only for one agent.
//
// Daemon only: it needs a filesystem write on the remote, which the daemon
// exposes and a bare SSH session does not.

import { decodeBase64 } from './base64';
import type { TransferBackend } from './files';

/** Upload chunk size. Matches the browser's own uploads. */
const CHUNK = 64 * 1024;

/** Refuses anything larger, so one paste cannot stall a session. */
export const MAX_IMAGE_BYTES = 12 * 1024 * 1024;

/** Where pasted images are written on the remote host. */
export const PASTE_DIR = '.remotly/pasted';

export interface PastedImage {
  /** Standard base64 PNG. */
  data: string;
  width: number;
  height: number;
}

/**
 * Names the file for a pasted image.
 *
 * The timestamp keeps two pastes in one session apart, and the name carries no
 * user input, so it cannot escape the directory it is written to.
 */
export function pastedImageName(now: number): string {
  const stamp = new Date(now)
    .toISOString()
    .replace(/[:.]/g, '-')
    .replace('T', '_')
    .replace('Z', '');
  return `paste_${stamp}.png`;
}

/**
 * Quotes a path for a POSIX shell.
 *
 * Single quotes protect everything except a single quote itself, which is
 * closed, escaped, and reopened. Without this a filename could run a command,
 * and the name is built here but the directory comes from the remote.
 */
export function shellQuote(path: string): string {
  return `'${path.replace(/'/g, `'\\''`)}'`;
}

export interface ImagePasteResult {
  /** Absolute remote path the image was written to. */
  path: string;
  /** What to type into the session, already quoted. */
  text: string;
}

/**
 * Writes a clipboard image to the remote host and returns what to type.
 *
 * [home] is the remote home directory, used to place the file somewhere the
 * agent can read. Throws when the image is too large or the upload fails; the
 * caller reports it rather than typing a path to a file that is not there.
 */
export async function pasteImage(
  image: PastedImage,
  transfers: TransferBackend,
  home: string,
  now: number = Date.now(),
): Promise<ImagePasteResult> {
  const bytes = decodeBase64(image.data);
  if (bytes.length === 0) throw new Error('The clipboard image was empty.');
  if (bytes.length > MAX_IMAGE_BYTES) {
    throw new Error('That image is too large to paste.');
  }

  const dir = `${home.replace(/\/+$/, '')}/${PASTE_DIR}`;
  const path = `${dir}/${pastedImageName(now)}`;

  const handle = await transfers.startUpload(path, bytes.length, 'replace');
  try {
    let offset = 0;
    while (offset < bytes.length) {
      const end = Math.min(offset + CHUNK, bytes.length);
      await transfers.writeChunk(
        handle.id,
        offset,
        bytes.subarray(offset, end),
      );
      offset = end;
    }
    await transfers.completeUpload(handle.id);
  } catch (e) {
    await transfers.cancel(handle.id).catch(() => undefined);
    throw e;
  }

  // A trailing space so the path does not run into whatever is typed next.
  return { path, text: `${shellQuote(path)} ` };
}
