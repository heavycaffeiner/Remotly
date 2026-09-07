// App-side boundary for the native file I/O (M4-06 transfer UI). The app never
// holds a local path: pickFile launches the system picker and the chosen
// content URI arrives as an event; readChunk/writeChunk move bytes between that
// URI and the transfer engine. The native side reads/writes through the
// ContentResolver, so scoped-storage rules and provider quirks stay on Android.

import { AppState } from 'react-native';
import NativeFileIO from '../specs/NativeRemotlyFileIO';
import { decodeBase64, encodeBase64 } from './base64';
import { log } from './log';

const PICKED_EVENT = 'remotly.file.picked';
const SINK_EVENT = 'remotly.file.sink';

export interface PickedFile {
  uri: string;
  name: string;
  // -1 when the content provider does not report a size.
  size: number;
  /**
   * Which pick produced this: "upload", "image", "download", or "folder".
   *
   * onPicked is one process-wide event with several subscribers, so each one
   * checks this rather than acting on another screen's pick.
   */
  mode: string;
}

// The module rejects with the native failure as an Error; keep that shape.
function toError(e: unknown): Error {
  return new Error((e as Error)?.message ?? 'file bridge call failed');
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

type Emitter = (handler: (arg: unknown) => void) => { remove(): void };

function parsePicked(data: Record<string, unknown>): PickedFile {
  const uri = typeof data.uri === 'string' ? data.uri : '';
  const name = typeof data.name === 'string' ? data.name : '';
  const size = typeof data.size === 'number' ? data.size : -1;
  const mode = typeof data.mode === 'string' ? data.mode : '';
  return { uri, name, size, mode };
}

// Launches the system picker. [mode] "upload" opens an existing document,
// "image" opens one filtered to images, and "download" creates a destination.
// The result is delivered via onPick/onSink, not this promise (which resolves
// once the picker is launched).
export function pickFile(
  mode: 'upload' | 'image' | 'download',
  name?: string,
): Promise<void> {
  return toVoid(NativeFileIO.pick(mode, name ?? ''));
}

/**
 * Opens the image picker and resolves with the choice, or null when the user
 * backed out.
 *
 * The native side reports a choice as an event and reports a cancellation not
 * at all, so a bare wait on the event would hang forever on a back press. The
 * subscription is therefore torn down when the app returns to the foreground
 * without one having arrived: that is the point at which the picker is known
 * to be gone.
 */
export function pickImage(): Promise<PickedFile | null> {
  const { promise, resolve, reject } =
    Promise.withResolvers<PickedFile | null>();

  let settled = false;
  let stopPick: (() => void) | null = null;
  let appStateSub: { remove: () => void } | null = null;

  const finish = (result: PickedFile | null): void => {
    if (settled) return;
    settled = true;
    stopPick?.();
    appStateSub?.remove();
    resolve(result);
  };
  stopPick = onPick(f => {
    // Another screen's pick, delivered on the same process-wide event.
    if (f.mode !== 'image') return;
    finish(f);
  });
  // The picker is a separate activity, so this app is inactive while it is up.
  // Returning to active with no event means it closed with no choice.
  appStateSub = AppState.addEventListener('change', state => {
    if (state !== 'active') return;
    // One turn of the loop, so a choice already in flight wins the race
    // against the foreground notification that follows it.
    setTimeout(() => finish(null), 0);
  });
  if (settled) {
    stopPick();
    appStateSub.remove();
    return promise;
  }

  pickFile('image').catch(e => {
    if (settled) return;
    settled = true;
    stopPick?.();
    appStateSub?.remove();
    reject(toError(e));
  });

  return promise;
}

/**
 * Asks for a download folder and keeps access to it.
 *
 * The result arrives on onSink, like a download destination, because that is
 * what it is used as. Holding a folder is what lets a download check for an
 * existing file before creating anything: the create-document picker resolves
 * a collision by renaming and never tells the app one happened.
 */
export function pickFolder(): Promise<void> {
  return toVoid(NativeFileIO.pickFolder());
}

/** True when a stored folder grant is still usable. */
export function hasFolderAccess(treeUri: string): Promise<boolean> {
  if (treeUri === '') return Promise.resolve(false);
  return NativeFileIO.hasFolderAccess(treeUri).catch(() => false);
}

/** The URI of `name` in the folder, or null when the name is free. */
export async function findInFolder(
  treeUri: string,
  name: string,
): Promise<string | null> {
  const uri = await NativeFileIO.findInFolder(treeUri, name).catch(e => {
    throw toError(e);
  });
  return uri === '' ? null : uri;
}

/** Creates `name` in the folder and returns the document URI. */
export function createInFolder(treeUri: string, name: string): Promise<string> {
  return NativeFileIO.createInFolder(treeUri, name).catch(e => {
    throw toError(e);
  });
}

function subscribe(
  emitter: Emitter,
  logName: string,
  handler: (f: PickedFile) => void,
): () => void {
  const raw: (event: unknown) => void = event => {
    if (event === null || event === undefined) return;
    const picked = parsePicked(event as Record<string, unknown>);
    if (picked.uri === '') return;
    try {
      handler(picked);
    } catch (e) {
      log.warn('file pick handler failed', {
        event: logName,
        error: String(e),
      });
    }
  };
  const sub = emitter(raw);
  return () => {
    sub.remove();
  };
}

export function onPick(handler: (f: PickedFile) => void): () => void {
  return subscribe(NativeFileIO.onPicked as Emitter, PICKED_EVENT, handler);
}

export function onSink(handler: (f: PickedFile) => void): () => void {
  return subscribe(NativeFileIO.onSink as Emitter, SINK_EVENT, handler);
}

// Reads up to [maxBytes] from [uri] starting at [offset].
export async function readChunk(
  uri: string,
  offset: number,
  maxBytes: number,
): Promise<{ data: Uint8Array; bytesRead: number }> {
  const d = await NativeFileIO.readChunk(uri, offset, maxBytes).catch(e => {
    throw toError(e);
  });
  const data =
    typeof d.data === 'string' && d.data !== ''
      ? decodeBase64(d.data)
      : new Uint8Array(0);
  return { data, bytesRead: typeof d.bytesRead === 'number' ? d.bytesRead : 0 };
}

// UTF-8 decode, hand-rolled because Hermes (and the RN tsconfig, which omits
// the dom lib) provides no TextDecoder.
/* eslint-disable no-bitwise -- utf-8 is inherently bitwise */
function utf8decode(bytes: Uint8Array): string {
  let out = '';
  let i = 0;
  while (i < bytes.length) {
    const b0 = bytes[i];
    let cp: number;
    if (b0 < 0x80) {
      cp = b0;
      i += 1;
    } else if ((b0 & 0xe0) === 0xc0) {
      cp = ((b0 & 0x1f) << 6) | (bytes[i + 1] & 0x3f);
      i += 2;
    } else if ((b0 & 0xf0) === 0xe0) {
      cp =
        ((b0 & 0x0f) << 12) |
        ((bytes[i + 1] & 0x3f) << 6) |
        (bytes[i + 2] & 0x3f);
      i += 3;
    } else {
      cp =
        ((b0 & 0x07) << 18) |
        ((bytes[i + 1] & 0x3f) << 12) |
        ((bytes[i + 2] & 0x3f) << 6) |
        (bytes[i + 3] & 0x3f);
      i += 4;
    }
    out += String.fromCodePoint(cp);
  }
  return out;
}
/* eslint-enable no-bitwise */

// Reads a whole content URI as UTF-8 text by looping readChunk until a short
// chunk signals end of file. Used to import a private key file into the SSH
// host form.
export async function readFileText(uri: string): Promise<string> {
  let offset = 0;
  let out = '';
  for (;;) {
    const res = await readChunk(uri, offset, 65536);
    const bytes = res.data;
    out += utf8decode(bytes);
    if (bytes.length < 65536) break;
    offset += bytes.length;
  }
  return out;
}

// Appends [data] to the document at [uri].
export async function writeChunk(
  uri: string,
  data: Uint8Array,
): Promise<number> {
  const d = await NativeFileIO.writeChunk(uri, encodeBase64(data)).catch(e => {
    throw toError(e);
  });
  return typeof d.bytesWritten === 'number' ? d.bytesWritten : 0;
}

// Drops the native side's cached streams for [uri].
/**
 * Deletes a download destination that was never filled.
 *
 * The picker creates the file up front, so a transfer that fails or is
 * cancelled leaves a truncated file under the name the user chose. Use this
 * rather than release wherever the bytes on disk are not worth keeping.
 */
export function discard(uri: string): Promise<void> {
  return toVoid(NativeFileIO.discard(uri));
}

export function release(uri: string): Promise<void> {
  return toVoid(NativeFileIO.release(uri));
}
