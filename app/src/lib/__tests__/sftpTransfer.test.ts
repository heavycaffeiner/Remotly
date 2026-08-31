import { SftpTransferBackend } from '../sftpTransfer';
import NativeSftp, {
  type SftpTransferEvent,
} from '../../specs/NativeRemotlySftp';
import { encodeBase64 } from '../base64';

const native = NativeSftp as unknown as {
  startDownload: jest.Mock;
  startDownloadToUri: jest.Mock;
  startUpload: jest.Mock;
  writeChunk: jest.Mock;
  completeUpload: jest.Mock;
  cancelTransfer: jest.Mock;
  onTransfer: jest.Mock;
};

// Captures the module's subscription so a test can emit as the native side
// does, from outside the promise that hands back the transfer id.
let emit: (event: SftpTransferEvent) => void = () => undefined;

jest.mock('../../specs/NativeRemotlySftp', () => ({
  __esModule: true,
  default: {
    startDownload: jest.fn(),
    startDownloadToUri: jest.fn(),
    startUpload: jest.fn(async () => 'up-1'),
    writeChunk: jest.fn(async () => 0),
    completeUpload: jest.fn(async () => undefined),
    cancelTransfer: jest.fn(async () => undefined),
    onTransfer: jest.fn((cb: (e: SftpTransferEvent) => void) => {
      emit = cb;
      return { remove: jest.fn() };
    }),
  },
}));

function chunkEvent(
  id: string,
  offset: number,
  body: string,
): SftpTransferEvent {
  return {
    id,
    offset,
    data: encodeBase64(new TextEncoder().encode(body)),
  } as SftpTransferEvent;
}

describe('SftpTransferBackend downloads', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('delivers chunks that arrive after the id is known', async () => {
    native.startDownload.mockResolvedValueOnce('down-1');
    const backend = new SftpTransferBackend('host-1');
    const seen: string[] = [];

    await backend.startDownload(
      '/remote/f.bin',
      (_o, b) => seen.push(new TextDecoder().decode(b)),
      () => undefined,
      () => undefined,
    );
    emit(chunkEvent('down-1', 0, 'hello'));

    expect(seen).toEqual(['hello']);
  });

  /**
   * The native side starts reading as soon as startDownload is called and
   * emits on its own thread, while the id only reaches JS when the promise
   * resolves. Chunks in that window were dropped, which silently truncated the
   * head of every file that transferred quickly enough.
   */
  it('replays chunks that arrived before the id came back', async () => {
    let release: (id: string) => void = () => undefined;
    native.startDownload.mockReturnValueOnce(
      new Promise<string>(resolve => {
        release = resolve;
      }),
    );
    const backend = new SftpTransferBackend('host-1');
    const seen: string[] = [];

    const started = backend.startDownload(
      '/remote/f.bin',
      (_o, b) => seen.push(new TextDecoder().decode(b)),
      () => undefined,
      () => undefined,
    );
    // The bridge is already streaming, but JS does not have the id yet.
    emit(chunkEvent('down-2', 0, 'head'));
    emit(chunkEvent('down-2', 4, 'tail'));
    release('down-2');
    await started;

    expect(seen).toEqual(['head', 'tail']);
  });

  it('replays a completion that landed early', async () => {
    let release: (id: string) => void = () => undefined;
    native.startDownload.mockReturnValueOnce(
      new Promise<string>(resolve => {
        release = resolve;
      }),
    );
    const backend = new SftpTransferBackend('host-1');
    let total = -1;

    const started = backend.startDownload(
      '/remote/f.bin',
      () => undefined,
      t => {
        total = t;
      },
      () => undefined,
    );
    emit(chunkEvent('down-3', 0, 'body'));
    emit({ id: 'down-3', offset: 0, done: 4 } as SftpTransferEvent);
    release('down-3');
    await started;

    expect(total).toBe(4);
  });

  it('surfaces an error that landed early', async () => {
    let release: (id: string) => void = () => undefined;
    native.startDownload.mockReturnValueOnce(
      new Promise<string>(resolve => {
        release = resolve;
      }),
    );
    const backend = new SftpTransferBackend('host-1');
    let failure = '';

    const started = backend.startDownload(
      '/remote/f.bin',
      () => undefined,
      () => undefined,
      m => {
        failure = m;
      },
    );
    emit({
      id: 'down-4',
      offset: 0,
      error: 'permission denied',
    } as SftpTransferEvent);
    release('down-4');
    await started;

    expect(failure).toBe('permission denied');
  });

  /**
   * The direct path keeps file bytes out of JS entirely: the native side
   * writes the content URI itself and only reports how far it has got. Routing
   * bytes through JS cost a base64 encode, a bridge crossing, and a JS turn
   * per chunk, all scaling with file size.
   */
  it('reports progress without carrying bytes', async () => {
    native.startDownloadToUri.mockResolvedValueOnce('down-uri-1');
    const backend = new SftpTransferBackend('host-1');
    const progress: number[] = [];
    let total = -1;

    await backend.startDownloadToUri(
      '/remote/big.bin',
      'content://dest/1',
      r => progress.push(r),
      t => {
        total = t;
      },
      () => undefined,
    );
    emit({ id: 'down-uri-1', offset: 524288 } as SftpTransferEvent);
    emit({ id: 'down-uri-1', offset: 1048576 } as SftpTransferEvent);
    emit({
      id: 'down-uri-1',
      offset: 1200000,
      done: 1200000,
    } as SftpTransferEvent);

    expect(progress).toEqual([524288, 1048576]);
    expect(total).toBe(1200000);
  });

  it('passes the destination uri to the bridge', async () => {
    native.startDownloadToUri.mockResolvedValueOnce('down-uri-2');
    const backend = new SftpTransferBackend('host-9');

    await backend.startDownloadToUri(
      '/remote/f.bin',
      'content://dest/2',
      () => undefined,
      () => undefined,
      () => undefined,
    );

    expect(native.startDownloadToUri).toHaveBeenCalledWith(
      'host-9',
      '/remote/f.bin',
      'content://dest/2',
      0,
    );
  });

  it('surfaces a direct download failure', async () => {
    native.startDownloadToUri.mockResolvedValueOnce('down-uri-3');
    const backend = new SftpTransferBackend('host-1');
    let failure = '';

    await backend.startDownloadToUri(
      '/remote/f.bin',
      'content://dest/3',
      () => undefined,
      () => undefined,
      m => {
        failure = m;
      },
    );
    emit({
      id: 'down-uri-3',
      offset: 0,
      error: 'disk full',
    } as SftpTransferEvent);

    expect(failure).toBe('disk full');
  });

  /** A stray id must not accumulate a file's worth of chunks in memory. */
  it('bounds what it holds for an id that never arrives', async () => {
    native.startDownload.mockResolvedValueOnce('down-5');
    const backend = new SftpTransferBackend('host-1');
    const seen: string[] = [];

    for (let i = 0; i < 500; i += 1) emit(chunkEvent('ghost', i, 'x'));

    await backend.startDownload(
      '/remote/f.bin',
      (_o, b) => seen.push(new TextDecoder().decode(b)),
      () => undefined,
      () => undefined,
    );
    emit(chunkEvent('down-5', 0, 'real'));

    expect(seen).toEqual(['real']);
  });
});
