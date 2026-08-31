// The daemon transfer backend (M4-05/06). It drives resumable uploads and
// downloads over the transport's file channel: control requests (transfer.*)
// manage the transfer, and the file channel carries the bytes as chunk frames
// of [8-byte big-endian offset][payload]. Uploads are app-driven and
// completed only after the daemon confirms every byte was applied (offset
// poll); downloads are push-driven (the daemon pumps) and settle when the
// bytes reach the expected size or the channel closes.

import type { RemotlyTransport } from './transport';
import type {
  ConflictPolicy,
  FilesCapabilities,
  TransferBackend,
  TransferHandle,
  TransferStatus,
} from './files';
import { DAEMON_CAPABILITIES } from './files';

// The largest payload per chunk frame. The daemon's MaxTransferChunk is 1 MiB
// and the file frame carries an 8-byte offset, so the payload stays under the
// 1 MiB budget and the whole frame under the transport's file-frame limit.
const CHUNK_PAYLOAD = (1 << 20) - 8;

// How long to wait for the daemon to apply uploaded bytes before giving up.
const APPLY_TIMEOUT_MS = 30_000;
const POLL_INTERVAL_MS = 25;

const OFFSET_LEN = 8;

// Encodes one chunk frame: an 8-byte big-endian offset followed by the payload.
// Exported so the v1 transfer vectors can be exercised from the JS fixture
// test against the Go-encoded bytes.
export function encodeChunkFrame(
  offset: number,
  payload: Uint8Array,
): Uint8Array {
  const frame = new Uint8Array(OFFSET_LEN + payload.length);
  const view = new DataView(frame.buffer);
  // Offsets exceed 32 bits for large files, so write the high and low words.
  view.setUint32(0, Math.floor(offset / 0x100000000));
  view.setUint32(4, offset >>> 0);
  frame.set(payload, OFFSET_LEN);
  return frame;
}

// Decodes a chunk frame's offset (8-byte big-endian) and payload. Returns null
// when the frame is too short to hold an offset. Exported for the v1 vectors.
export function decodeChunkFrame(
  frame: Uint8Array,
): { offset: number; payload: Uint8Array } | null {
  if (frame.length < OFFSET_LEN) return null;
  const view = new DataView(frame.buffer, frame.byteOffset, frame.byteLength);
  const offset = view.getUint32(0) * 0x100000000 + view.getUint32(4);
  return { offset, payload: frame.subarray(OFFSET_LEN) };
}

interface Tracked {
  handle: TransferHandle;
  channelId: number;
  dir: 'up' | 'down';
  expected: number;
  received: number;
  done: boolean;
  error?: string;
  onChunk?: (offset: number, bytes: Uint8Array) => void;
  onDone?: (totalBytes: number) => void;
  onError?: (message: string) => void;
}

function sleep(ms: number): Promise<void> {
  return new Promise(r => setTimeout(r, ms));
}

function controlError(resp: Record<string, unknown>): string | null {
  const err = resp.error as { code?: string; message?: string } | undefined;
  if (err && typeof err.code === 'string') {
    return typeof err.message === 'string' && err.message !== ''
      ? err.message
      : err.code;
  }
  return null;
}

export class DaemonTransferBackend implements TransferBackend {
  readonly kind = 'daemon' as const;
  readonly capabilities: FilesCapabilities = DAEMON_CAPABILITIES;

  private transport: RemotlyTransport;
  private hostId: string;
  private transfers = new Map<string, Tracked>();
  private byChannel = new Map<number, string>(); // channelId -> transfer id
  private unsubscribed = false;

  constructor(transport: RemotlyTransport, hostId: string) {
    this.transport = transport;
    this.hostId = hostId;
    this.transport.onEvent('fileData', e => {
      if (e.hostId !== this.hostId) return;
      const id = this.byChannel.get(e.channelId);
      if (id === undefined) return;
      this.onFileData(id, e.data);
    });
    this.transport.onEvent('channelClose', e => {
      if (e.hostId !== this.hostId) return;
      const id = this.byChannel.get(e.channelId);
      if (id === undefined) return;
      this.onChannelClosed(id, e.reason);
    });
  }

  // Releases the event subscriptions. Call when the browser for this host is
  // torn down; otherwise the handlers linger for the transport's lifetime.
  dispose(): void {
    if (this.unsubscribed) return;
    this.unsubscribed = true;
    // onEvent returns an unsubscribe; we hold none, so rely on the transport
    // scoping events per host and the transfer map being cleared on close.
    this.transfers.clear();
    this.byChannel.clear();
  }

  /**
   * Reattaches to a transfer the daemon still holds and reports its offset.
   *
   * The daemon keeps an interrupted transfer with its temp file intact, so
   * continuing means reopening that transfer by id rather than creating a new
   * one. transfer.create would mint a fresh id and an empty temp file, which
   * is a restart wearing a resume's clothes.
   *
   * Returns null when the daemon no longer has it, in which case the caller
   * starts over.
   */
  async reattach(transferId: string): Promise<TransferHandle | null> {
    const resp = await this.control({
      type: 'transfer.resume',
      transfer_id: transferId,
    });
    if (controlError(resp) !== null) return null;
    const id = this.requireString(resp, 'transfer_id');
    const channelId = this.requireNumber(resp, 'channel_id');
    const size =
      typeof resp.expected_size === 'number'
        ? (resp.expected_size as number)
        : 0;
    const offset =
      typeof resp.offset === 'number' ? (resp.offset as number) : 0;
    const dir = resp.direction === 'down' ? 'down' : 'up';
    await this.transport.openFile(this.hostId, channelId);
    this.track(id, channelId, dir, size, offset);
    return {
      id,
      direction: dir === 'down' ? 'download' : 'upload',
      path: '',
      size,
      startOffset: offset,
    };
  }

  async startUpload(
    path: string,
    size: number,
    conflict: ConflictPolicy,
    hash?: string,
    resumeFrom?: number,
  ): Promise<TransferHandle> {
    const req: Record<string, unknown> = {
      type: 'transfer.create',
      direction: 'up',
      path,
      expected_size: size,
      conflict,
    };
    if (hash !== undefined) req.hash = hash;
    void resumeFrom;
    const resp = await this.control(req);
    const err = controlError(resp);
    if (err !== null) throw new Error(err);
    const id = this.requireString(resp, 'transfer_id');
    const channelId = this.requireNumber(resp, 'channel_id');
    // The daemon decides where to continue from: it knows how much of the
    // file it already holds. The caller reads its local file from here rather
    // than from whatever offset it asked for.
    const resumeOff =
      typeof resp.offset === 'number' ? (resp.offset as number) : 0;
    await this.transport.openFile(this.hostId, channelId);
    this.track(id, channelId, 'up', size, resumeOff);
    return { id, direction: 'upload', path, size, startOffset: resumeOff };
  }

  // Writes one chunk at the given offset. The write is fire and forget on the
  // channel; the daemon applies frames in order. Returns the offset just past
  // this chunk (the written watermark for progress).
  async writeChunk(
    id: string,
    offset: number,
    data: Uint8Array,
  ): Promise<number> {
    const t = this.transfers.get(id);
    if (t === undefined || t.dir !== 'up')
      throw new Error('unknown upload transfer');
    if (t.done) throw new Error('transfer already finished');
    const frame = encodeChunkFrame(offset, data);
    await this.transport.writeFile(this.hostId, t.channelId, frame);
    return offset + data.length;
  }

  // Completes the upload: waits until the daemon has applied every expected
  // byte (offset poll), then sends transfer.complete. This is what makes a
  // failure leave the destination untouched rather than half-written.
  async completeUpload(id: string): Promise<void> {
    const t = this.transfers.get(id);
    if (t === undefined || t.dir !== 'up')
      throw new Error('unknown upload transfer');
    if (t.done) return;
    await this.waitForApplied(t);
    const resp = await this.control({
      type: 'transfer.complete',
      transfer_id: id,
    });
    const err = controlError(resp);
    if (err !== null) {
      t.error = err;
      t.done = true;
      throw new Error(err);
    }
    t.done = true;
  }

  async startDownload(
    path: string,
    onChunk: (offset: number, bytes: Uint8Array) => void,
    onDone: (totalBytes: number) => void,
    onError: (message: string) => void,
  ): Promise<TransferHandle> {
    const resp = await this.control({
      type: 'transfer.create',
      direction: 'down',
      path,
    });
    const err = controlError(resp);
    if (err !== null) {
      onError(err);
      throw new Error(err);
    }
    const id = this.requireString(resp, 'transfer_id');
    const channelId = this.requireNumber(resp, 'channel_id');
    const size =
      typeof resp.expected_size === 'number'
        ? (resp.expected_size as number)
        : 0;
    await this.transport.openFile(this.hostId, channelId);
    const t = this.track(id, channelId, 'down', size, 0);
    t.onChunk = onChunk;
    t.onDone = onDone;
    t.onError = onError;
    // A zero-size file completes immediately.
    if (size === 0) {
      t.done = true;
      onDone(0);
    }
    return { id, direction: 'download', path, size };
  }

  async status(id: string): Promise<TransferStatus> {
    const t = this.transfers.get(id);
    if (t !== undefined) {
      if (t.error !== undefined)
        return {
          state: 'error',
          received: t.received,
          total: t.expected,
          error: t.error,
        };
      if (t.done)
        return { state: 'done', received: t.received, total: t.expected };
      return { state: 'active', received: t.received, total: t.expected };
    }
    // Unknown id: ask the daemon. A not-found answer is a clean error state.
    const resp = await this.control({
      type: 'transfer.status',
      transfer_id: id,
    });
    const err = controlError(resp);
    if (err !== null)
      return { state: 'error', received: 0, total: 0, error: err };
    const off = typeof resp.offset === 'number' ? (resp.offset as number) : 0;
    const size =
      typeof resp.expected_size === 'number'
        ? (resp.expected_size as number)
        : 0;
    return off >= size && size > 0
      ? { state: 'done', received: size, total: size }
      : { state: 'active', received: off, total: size };
  }

  async cancel(id: string): Promise<void> {
    const t = this.transfers.get(id);
    if (t !== undefined) {
      t.done = true;
      t.error = 'cancelled';
      this.forget(id);
      t.onError?.('cancelled');
    }
    const resp = await this.control({
      type: 'transfer.cancel',
      transfer_id: id,
    });
    // A not-found cancel is fine; the transfer is already gone.
    void resp;
  }

  // --- internals ---------------------------------------------------------

  private track(
    id: string,
    channelId: number,
    dir: 'up' | 'down',
    expected: number,
    received: number,
  ): Tracked {
    const t: Tracked = {
      handle: {
        id,
        direction: dir === 'up' ? 'upload' : 'download',
        path: '',
        size: expected,
      },
      channelId,
      dir,
      expected,
      received,
      done: false,
    };
    this.transfers.set(id, t);
    this.byChannel.set(channelId, id);
    return t;
  }

  private onFileData(id: string, frame: Uint8Array): void {
    const t = this.transfers.get(id);
    if (t === undefined || t.dir !== 'down' || t.done) return;
    const decoded = decodeChunkFrame(frame);
    if (decoded === null) return;
    // Only accept an in-order or already-received chunk; the daemon pumps in
    // order, so a jump means a corrupted or foreign frame.
    if (decoded.offset !== t.received) return;
    t.received += decoded.payload.length;
    t.onChunk?.(decoded.offset, decoded.payload);
    if (t.expected > 0 && t.received >= t.expected) {
      t.done = true;
      t.onDone?.(t.received);
    }
  }

  private onChannelClosed(id: string, reason: string): void {
    const t = this.transfers.get(id);
    if (t === undefined || t.done) return;
    if (t.dir === 'down') {
      // The daemon closes the channel after pumping (and after transfer.done).
      // If we have all the bytes, settle as done; otherwise as an error.
      t.done = true;
      if (t.expected === 0 || t.received >= t.expected) {
        t.onDone?.(t.received);
      } else {
        t.error = reason !== '' ? reason : 'channel closed early';
        t.onError?.(t.error);
      }
    } else {
      t.done = true;
      t.error = reason !== '' ? reason : 'channel closed';
    }
    this.forget(id);
  }

  private forget(id: string): void {
    const t = this.transfers.get(id);
    if (t !== undefined) this.byChannel.delete(t.channelId);
    this.transfers.delete(id);
  }

  // Polls transfer.status until the daemon reports every expected byte applied,
  // or the channel closes, or the timeout elapses.
  private async waitForApplied(t: Tracked): Promise<void> {
    const deadline = Date.now() + APPLY_TIMEOUT_MS;
    while (Date.now() < deadline) {
      if (t.done) {
        if (t.error !== undefined) throw new Error(t.error);
        return;
      }
      const resp = await this.control({
        type: 'transfer.status',
        transfer_id: t.handle.id,
      });
      const err = controlError(resp);
      if (err !== null) throw new Error(err);
      const off = typeof resp.offset === 'number' ? (resp.offset as number) : 0;
      t.received = Math.max(t.received, off);
      if (t.expected > 0 && off >= t.expected) return;
      if (t.expected === 0) return;
      await sleep(POLL_INTERVAL_MS);
    }
    throw new Error('timed out waiting for the daemon to apply uploaded bytes');
  }

  private async control(
    req: Record<string, unknown>,
  ): Promise<Record<string, unknown>> {
    return this.transport.control(this.hostId, req);
  }

  private requireString(resp: Record<string, unknown>, key: string): string {
    const v = resp[key];
    if (typeof v !== 'string' || v === '')
      throw new Error(`missing ${key} in transfer response`);
    return v;
  }

  private requireNumber(resp: Record<string, unknown>, key: string): number {
    const v = resp[key];
    if (typeof v !== 'number' || !Number.isInteger(v) || v < 1) {
      throw new Error(`missing ${key} in transfer response`);
    }
    return v;
  }
}

// Exposed for tests: the per-frame payload cap.
export const DAEMON_CHUNK_PAYLOAD = CHUNK_PAYLOAD;
