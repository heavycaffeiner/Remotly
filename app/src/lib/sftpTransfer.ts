// SFTP transfers, adapting the native bridge to the TransferBackend contract.
//
// Uploads are app-driven: the caller writes chunks in order and completes.
// Downloads are push-driven: chunks arrive on the native event emitter and are
// routed to the caller's handler by transfer id.
//
// A transfer is registered before the native call resolves nothing else can
// deliver its id, so the emitter subscription is process-wide and started once
// rather than per transfer.

import NativeSftp, { type SftpTransferEvent } from '../specs/NativeRemotlySftp';
import { decodeBase64, encodeBase64 } from './base64';
import {
  SFTP_CAPABILITIES,
  type ConflictPolicy,
  type FilesCapabilities,
  type TransferBackend,
  type TransferHandle,
  type TransferStatus,
} from './files';

interface DownloadSink {
  onChunk: (offset: number, bytes: Uint8Array) => void;
  onDone: (total: number) => void;
  onError: (message: string) => void;
  received: number;
}

const sinks = new Map<string, DownloadSink>();

/**
 * Events that arrived before their sink was registered.
 *
 * The native side starts reading as soon as startDownload is called and emits
 * on its own thread, while the id only reaches this module when the promise
 * resolves a turn later. Chunks in that window used to be dropped, which
 * truncated the head of a file, so they are held here and replayed.
 */
const pending = new Map<string, SftpTransferEvent[]>();

/** Bounds the hold: a stray id must not accumulate a file's worth of chunks. */
const MAX_PENDING_EVENTS = 64;

let subscription: { remove: () => void } | null = null;

/** Starts the shared subscription on the first download. */
function ensureSubscribed(): void {
  if (subscription !== null) return;
  subscription = NativeSftp.onTransfer((event: SftpTransferEvent) => {
    const sink = sinks.get(event.id);
    if (sink === undefined) {
      // The id has not come back from the bridge yet. Hold the event rather
      // than discard it; startDownload replays these once it registers.
      const held = pending.get(event.id) ?? [];
      if (held.length < MAX_PENDING_EVENTS) {
        held.push(event);
        pending.set(event.id, held);
      }
      return;
    }
    deliver(sink, event);
  });
}

/** Routes one event to its sink. */
function deliver(sink: DownloadSink, event: SftpTransferEvent): void {
  if (typeof event.error === 'string') {
    sinks.delete(event.id);
    sink.onError(event.error);
    return;
  }
  if (typeof event.done === 'number') {
    sinks.delete(event.id);
    sink.onDone(event.done);
    return;
  }
  if (typeof event.data === 'string') {
    let bytes: Uint8Array;
    try {
      bytes = decodeBase64(event.data);
    } catch {
      sinks.delete(event.id);
      sink.onError('malformed chunk from the transfer bridge');
      return;
    }
    sink.received += bytes.length;
    sink.onChunk(event.offset, bytes);
    return;
  }
  // No data: a direct-to-URI download reporting how far it has got. The bytes
  // are already on disk, so offset is the running total rather than a
  // position to write at.
  sink.received = event.offset;
  sink.onChunk(event.offset, EMPTY);
}

const EMPTY = new Uint8Array(0);

function toError(e: unknown): Error {
  return new Error((e as Error)?.message ?? 'sftp transfer failed');
}

/**
 * Transfers for one host's SFTP connection.
 *
 * The host is fixed at construction because the native side keys connections
 * by host, so a backend instance and a connection are the same thing.
 */
export class SftpTransferBackend implements TransferBackend {
  readonly kind = 'sftp' as const;
  readonly capabilities: FilesCapabilities = SFTP_CAPABILITIES;

  /** Bytes accepted per transfer, for status polling. */
  private progress = new Map<string, number>();

  constructor(private readonly hostId: string) {}

  async startUpload(
    path: string,
    size: number,
    conflict: ConflictPolicy,
  ): Promise<TransferHandle> {
    const id = await NativeSftp.startUpload(this.hostId, path, conflict).catch(
      e => {
        throw toError(e);
      },
    );
    this.progress.set(id, 0);
    return { id, direction: 'upload', path, size };
  }

  async writeChunk(
    id: string,
    offset: number,
    data: Uint8Array,
  ): Promise<number> {
    const written = await NativeSftp.writeChunk(
      id,
      offset,
      encodeBase64(data),
    ).catch(e => {
      throw toError(e);
    });
    this.progress.set(id, offset + written);
    return written;
  }

  async completeUpload(id: string): Promise<void> {
    await NativeSftp.completeUpload(id).catch(e => {
      throw toError(e);
    });
    this.progress.delete(id);
  }

  async startDownload(
    path: string,
    onChunk: (offset: number, bytes: Uint8Array) => void,
    onDone: (totalBytes: number) => void,
    onError: (message: string) => void,
  ): Promise<TransferHandle> {
    ensureSubscribed();
    const id = await NativeSftp.startDownload(this.hostId, path).catch(e => {
      throw toError(e);
    });

    const sink: DownloadSink = {
      onChunk: (offset, bytes) => {
        this.progress.set(id, (this.progress.get(id) ?? 0) + bytes.length);
        onChunk(offset, bytes);
      },
      onDone: total => {
        this.progress.delete(id);
        onDone(total);
      },
      onError: message => {
        this.progress.delete(id);
        onError(message);
      },
      received: 0,
    };
    sinks.set(id, sink);
    this.progress.set(id, 0);
    // Anything that arrived while the id was still crossing the bridge, in
    // arrival order.
    const held = pending.get(id);
    if (held !== undefined) {
      pending.delete(id);
      for (const event of held) {
        if (!sinks.has(id)) break;
        deliver(sink, event);
      }
    }
    return { id, direction: 'download', path, size: -1 };
  }

  /**
   * Streams straight into a content URI, with no file bytes crossing into JS.
   *
   * Progress events carry `offset` as the running total and no `data`; the
   * single terminal event is unchanged.
   */
  async startDownloadToUri(
    path: string,
    uri: string,
    onProgress: (received: number) => void,
    onDone: (totalBytes: number) => void,
    onError: (message: string) => void,
    resumeFrom?: number,
  ): Promise<TransferHandle> {
    ensureSubscribed();
    const from = resumeFrom !== undefined && resumeFrom > 0 ? resumeFrom : 0;
    const id = await NativeSftp.startDownloadToUri(
      this.hostId,
      path,
      uri,
      from,
    ).catch(e => {
      throw toError(e);
    });

    const sink: DownloadSink = {
      onChunk: offset => {
        this.progress.set(id, offset);
        onProgress(offset);
      },
      onDone: total => {
        this.progress.delete(id);
        onDone(total);
      },
      onError: message => {
        this.progress.delete(id);
        onError(message);
      },
      received: 0,
    };
    sinks.set(id, sink);
    this.progress.set(id, 0);
    const held = pending.get(id);
    if (held !== undefined) {
      pending.delete(id);
      for (const event of held) {
        if (!sinks.has(id)) break;
        deliver(sink, event);
      }
    }
    return { id, direction: 'download', path, size: -1 };
  }

  async status(id: string): Promise<TransferStatus> {
    const received = this.progress.get(id);
    if (received === undefined) {
      return { state: 'done', received: 0, total: 0 };
    }
    return { state: 'active', received, total: -1 };
  }

  async cancel(id: string): Promise<void> {
    sinks.delete(id);
    pending.delete(id);
    this.progress.delete(id);
    await NativeSftp.cancelTransfer(id).catch(e => {
      throw toError(e);
    });
  }
}
