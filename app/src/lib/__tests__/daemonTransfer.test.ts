import { describe, it, expect, jest } from '@jest/globals';
import type { RemotlyTransport } from '../transport';
import { DaemonTransferBackend, DAEMON_CHUNK_PAYLOAD } from '../daemonTransfer';

// A mock transport that simulates the daemon's transfer state: uploads advance
// an applied offset as chunk frames arrive on the file channel; downloads are
// pumped by emitting fileData frames. This exercises the app half of the
// M4-05/06 transfer contract without a device.

const OFFSET_LEN = 8;

function encodeFrame(offset: number, payload: Uint8Array): Uint8Array {
  const f = new Uint8Array(OFFSET_LEN + payload.length);
  const v = new DataView(f.buffer);
  v.setUint32(0, Math.floor(offset / 0x100000000));
  v.setUint32(4, offset >>> 0);
  f.set(payload, OFFSET_LEN);
  return f;
}

function decodeFrame(f: Uint8Array): { offset: number; payload: Uint8Array } {
  const v = new DataView(f.buffer, f.byteOffset, f.byteLength);
  return {
    offset: v.getUint32(0) * 0x100000000 + v.getUint32(4),
    payload: f.subarray(OFFSET_LEN),
  };
}

interface FakeTransfer {
  direction: 'up' | 'down';
  expected: number;
  applied: number;
  bytes: number[];
  source?: number[]; // for downloads: the file content the daemon pumps
}

class MockTransport {
  controlFn: (
    hostId: string,
    req: Record<string, unknown>,
  ) => Promise<Record<string, unknown>>;
  openFileFn = jest.fn(async (_h: string, _ch: number) => undefined);
  writeFileFn = jest.fn(
    async (_h: string, _ch: number, _d: Uint8Array) => undefined,
  );
  handlers: Record<string, ((e: never) => void)[]> = {};

  constructor(controlFn: MockTransport['controlFn']) {
    this.controlFn = controlFn;
  }

  control(hostId: string, req: Record<string, unknown>) {
    return this.controlFn(hostId, req);
  }
  openFile(hostId: string, channelId: number) {
    return this.openFileFn(hostId, channelId);
  }
  writeFile(hostId: string, channelId: number, data: Uint8Array) {
    return this.writeFileFn(hostId, channelId, data);
  }
  onEvent(name: string, handler: (e: never) => void): () => void {
    (this.handlers[name] ??= []).push(handler);
    return () => {
      this.handlers[name] = (this.handlers[name] ?? []).filter(
        h => h !== handler,
      );
    };
  }
  emit(name: string, event: unknown) {
    for (const h of this.handlers[name] ?? []) h(event as never);
  }
  // The remaining interface methods are unused by the transfer backend.
  connect() {
    return Promise.resolve({});
  }
  close() {
    return Promise.resolve();
  }
  status() {
    return Promise.resolve({ state: 'ready' as const });
  }
  writeTerm() {
    return Promise.resolve();
  }
}

// Builds a mock daemon with one transfer pre-seeded. `preApplied` simulates a
// partially-uploaded transfer for resume.
function makeDaemon(opts: {
  direction: 'up' | 'down';
  size: number;
  source?: number[];
  preApplied?: number;
}) {
  const transfers = new Map<string, FakeTransfer>();
  const written: number[] = [];
  const channelId = 42;
  const transferId = 'tr-1';
  transfers.set(transferId, {
    direction: opts.direction,
    expected: opts.size,
    applied: opts.preApplied ?? 0,
    bytes: [],
    source: opts.source,
  });

  const transport = new MockTransport(async (_h, req) => {
    switch (req.type) {
      case 'transfer.create': {
        const t = transfers.get(transferId)!;
        return {
          id: 1,
          type: 'transfer.create',
          transfer_id: transferId,
          channel_id: channelId,
          direction: t.direction,
          expected_size: t.expected,
          hash: '',
          offset: t.applied,
        };
      }
      case 'transfer.status': {
        const t = transfers.get(transferId)!;
        return {
          id: 1,
          type: 'transfer.status',
          transfer_id: transferId,
          direction: t.direction,
          expected_size: t.expected,
          offset: t.applied,
        };
      }
      case 'transfer.complete': {
        const t = transfers.get(transferId)!;
        if (t.applied !== t.expected) {
          return {
            id: 1,
            type: 'transfer.complete',
            error: { code: 'transfer_incomplete', message: 'incomplete' },
          };
        }
        written.push(...t.bytes);
        return { id: 1, type: 'transfer.complete' };
      }
      case 'transfer.cancel':
        return { id: 1, type: 'transfer.cancel' };
      default:
        return {
          id: 1,
          type: req.type as string,
          error: { code: 'unknown', message: 'unknown type' },
        };
    }
  });

  // Applying an upload chunk frame advances the daemon's applied offset.
  transport.writeFileFn = jest.fn(async (_h, _ch, data) => {
    const t = transfers.get(transferId)!;
    const { offset, payload } = decodeFrame(data);
    if (offset !== t.applied) throw new Error('out-of-order upload chunk');
    for (const b of payload) t.bytes.push(b);
    t.applied = offset + payload.length;
  });

  return { transport, transfers, written, channelId, transferId };
}

describe('DaemonTransferBackend', () => {
  it('uploads chunks and completes only after every byte is applied', async () => {
    const payload = new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
    const { transport, written } = makeDaemon({
      direction: 'up',
      size: payload.length,
    });
    const backend = new DaemonTransferBackend(
      transport as unknown as RemotlyTransport,
      'host',
    );

    const handle = await backend.startUpload(
      '/dest.txt',
      payload.length,
      'fail',
    );
    expect(handle.direction).toBe('upload');
    expect(transport.openFileFn).toHaveBeenCalledWith('host', 42);

    let off = 0;
    while (off < payload.length) {
      const n = Math.min(DAEMON_CHUNK_PAYLOAD, payload.length - off);
      off = await backend.writeChunk(
        handle.id,
        off,
        payload.subarray(off, off + n),
      );
    }
    expect(off).toBe(payload.length);
    await backend.completeUpload(handle.id);
    expect(written).toEqual(Array.from(payload));
    expect(await backend.status(handle.id)).toMatchObject({ state: 'done' });
  });

  it('reports a resume offset for a partially uploaded transfer', async () => {
    const { transport } = makeDaemon({
      direction: 'up',
      size: 100,
      preApplied: 40,
    });
    const backend = new DaemonTransferBackend(
      transport as unknown as RemotlyTransport,
      'host',
    );
    const handle = await backend.startUpload('/dest.bin', 100, 'fail');
    // startUpload resolves with the resume offset folded into status.
    const st = await backend.status(handle.id);
    expect(st.received).toBe(40);
  });

  it('downloads pumped chunks in order and settles on done', async () => {
    const source = Array.from({ length: 25 }, (_, i) => (i * 7) % 256);
    const { transport } = makeDaemon({
      direction: 'down',
      size: source.length,
      source,
    });
    const backend = new DaemonTransferBackend(
      transport as unknown as RemotlyTransport,
      'host',
    );

    const got: number[] = [];
    let done = -1;
    let failed = '';
    const handle = await backend.startDownload(
      '/src.txt',
      (off, bytes) => {
        for (const b of bytes) got.push(b);
      },
      total => {
        done = total;
      },
      msg => {
        failed = msg;
      },
    );
    expect(handle.size).toBe(source.length);

    // Pump the file in three frames, in order.
    const frame1 = source.slice(0, 10);
    const frame2 = source.slice(10, 20);
    const frame3 = source.slice(20);
    transport.emit('fileData', {
      hostId: 'host',
      channelId: 42,
      data: encodeFrame(0, new Uint8Array(frame1)),
    });
    transport.emit('fileData', {
      hostId: 'host',
      channelId: 42,
      data: encodeFrame(10, new Uint8Array(frame2)),
    });
    transport.emit('fileData', {
      hostId: 'host',
      channelId: 42,
      data: encodeFrame(20, new Uint8Array(frame3)),
    });

    expect(failed).toBe('');
    expect(done).toBe(source.length);
    expect(got).toEqual(source);
  });

  it('rejects a foreign file channel id', async () => {
    const source = [1, 2, 3];
    const { transport } = makeDaemon({
      direction: 'down',
      size: source.length,
      source,
    });
    const backend = new DaemonTransferBackend(
      transport as unknown as RemotlyTransport,
      'host',
    );
    let failed = '';
    await backend.startDownload(
      '/src',
      () => {},
      () => {},
      m => {
        failed = m;
      },
    );
    // A frame for a channel the backend never opened must be ignored.
    transport.emit('fileData', {
      hostId: 'host',
      channelId: 999,
      data: encodeFrame(0, new Uint8Array([9])),
    });
    expect(failed).toBe('');
  });

  it('settles a truncated download as an error when the channel closes early', async () => {
    const source = [1, 2, 3, 4, 5];
    const { transport } = makeDaemon({
      direction: 'down',
      size: source.length,
      source,
    });
    const backend = new DaemonTransferBackend(
      transport as unknown as RemotlyTransport,
      'host',
    );
    let failed = '';
    let done = -1;
    await backend.startDownload(
      '/src',
      () => {},
      t => {
        done = t;
      },
      m => {
        failed = m;
      },
    );
    transport.emit('fileData', {
      hostId: 'host',
      channelId: 42,
      data: encodeFrame(0, new Uint8Array([1, 2])),
    });
    transport.emit('channelClose', {
      hostId: 'host',
      channelId: 42,
      reason: 'remote closed',
    });
    expect(done).toBe(-1);
    expect(failed).toBe('remote closed');
  });

  it('surfaces a create error as a rejection', async () => {
    const transport = new MockTransport(async (_h, req) => {
      if (req.type === 'transfer.create') {
        return {
          id: 1,
          type: 'transfer.create',
          error: { code: 'fs_not_found', message: 'no such file' },
        };
      }
      return { id: 1, type: req.type as string };
    });
    const backend = new DaemonTransferBackend(
      transport as unknown as RemotlyTransport,
      'host',
    );
    await expect(backend.startUpload('/missing', 10, 'fail')).rejects.toThrow(
      'no such file',
    );
  });
});
