// A download keeps running when the browser screen goes away.
//
// The screen owns a banner, not the transfer. Chunk handling used to be gated
// on the screen still claiming the transfer, so navigating to another tab
// mid-download dropped every later chunk: the file was silently truncated and
// nothing reported a failure.
//
// These exercise the shape of that handler rather than the screen, which needs
// a host, a connection, and a document picker to mount.

import {
  activeTransfers,
  advanceTransfer,
  listTransfers,
  registerTransfer,
  resetTransfers,
  settleTransfer,
} from '../../../lib/transfers';

jest.mock('../../../specs/NativeRemotlyFileIO', () => ({
  __esModule: true,
  default: { setTransfersActive: jest.fn().mockResolvedValue(undefined) },
}));

/**
 * The chunk handler as FilesScreen builds it: writes and progress are
 * unconditional, and only the caller's own banner is gated on ownership.
 */
function makeHandler(
  id: string,
  sink: number[],
  ownedId: () => string | null,
  onBanner: (received: number) => void,
): (bytes: Uint8Array) => void {
  let received = 0;
  return bytes => {
    sink.push(bytes.length);
    received += bytes.length;
    advanceTransfer(id, received);
    if (ownedId() !== id) return;
    onBanner(received);
  };
}

beforeEach(() => resetTransfers());

describe('a download whose screen has gone', () => {
  it('keeps writing chunks after the screen releases its claim', () => {
    registerTransfer(
      {
        id: 'd1',
        direction: 'download',
        path: '/remote/big.bin',
        name: 'big.bin',
        hostId: 'h1',
        total: 300,
      },
      () => {},
    );
    const written: number[] = [];
    let owned: string | null = 'd1';
    const banner: number[] = [];
    const onChunk = makeHandler(
      'd1',
      written,
      () => owned,
      n => banner.push(n),
    );

    onChunk(new Uint8Array(100));
    // The user switches tabs: the screen unmounts and drops its claim.
    owned = null;
    onChunk(new Uint8Array(100));
    onChunk(new Uint8Array(100));

    expect(written).toEqual([100, 100, 100]);
    expect(listTransfers()[0]?.transferred).toBe(300);
    // The banner stopped, which is correct: it belongs to a screen that is
    // no longer there.
    expect(banner).toEqual([100]);
  });

  it('still reports progress app-wide while backgrounded', () => {
    registerTransfer(
      {
        id: 'd2',
        direction: 'download',
        path: '/remote/f',
        name: 'f',
        hostId: 'h1',
        total: 200,
      },
      () => {},
    );
    const onChunk = makeHandler(
      'd2',
      [],
      () => null,
      () => {},
    );

    onChunk(new Uint8Array(50));
    onChunk(new Uint8Array(50));

    expect(activeTransfers()[0]?.transferred).toBe(100);
  });

  it('settles even though no screen is watching', () => {
    registerTransfer(
      {
        id: 'd3',
        direction: 'download',
        path: '/remote/f',
        name: 'f',
        hostId: 'h1',
        total: 10,
      },
      () => {},
    );
    advanceTransfer('d3', 10);
    settleTransfer('d3', 'done');

    expect(activeTransfers()).toHaveLength(0);
    expect(listTransfers()[0]?.phase).toBe('done');
  });
});
