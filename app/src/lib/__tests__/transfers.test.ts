import {
  activeTransfers,
  advanceTransfer,
  cancelTransfer,
  clearSettledTransfers,
  listTransfers,
  canRetryTransfer,
  registerTransfer,
  resetTransfers,
  retryTransfer,
  settleTransfer,
  subscribeTransfers,
} from '../transfers';

function add(id: string, cancel: () => void = () => {}): void {
  registerTransfer(
    {
      id,
      direction: 'download',
      path: `/remote/${id}`,
      name: id,
      hostId: 'host-1',
      total: 100,
    },
    cancel,
  );
}

beforeEach(() => resetTransfers());

describe('transfer store', () => {
  it('lists a registered transfer as active', () => {
    add('a');
    expect(activeTransfers().map(t => t.id)).toEqual(['a']);
  });

  it('reports progress', () => {
    add('a');
    advanceTransfer('a', 42);
    expect(listTransfers()[0]?.transferred).toBe(42);
  });

  it('stops reporting progress once settled', () => {
    add('a');
    settleTransfer('a', 'done');
    advanceTransfer('a', 99);
    expect(listTransfers()[0]?.transferred).toBe(0);
  });

  it('drops a settled transfer from the active list', () => {
    add('a');
    settleTransfer('a', 'done');
    expect(activeTransfers()).toHaveLength(0);
    expect(listTransfers()).toHaveLength(1);
  });

  it('keeps the failure reason', () => {
    add('a');
    settleTransfer('a', 'error', 'connection lost');
    expect(listTransfers()[0]?.error).toBe('connection lost');
  });

  // The whole point of the store: a screen can come and go.
  it('notifies subscribers and stops after unsubscribe', () => {
    const seen: number[] = [];
    const off = subscribeTransfers(list => seen.push(list.length));
    add('a');
    add('b');
    off();
    add('c');
    expect(seen).toEqual([0, 1, 2]);
  });

  it('runs the cancel hook and settles as cancelled', () => {
    let cancelled = false;
    add('a', () => {
      cancelled = true;
    });
    cancelTransfer('a');
    expect(cancelled).toBe(true);
    expect(listTransfers()[0]?.phase).toBe('cancelled');
  });

  // A throwing cancel must not leave the record stuck as active forever.
  it('settles even when the cancel hook throws', () => {
    add('a', () => {
      throw new Error('bridge gone');
    });
    expect(() => cancelTransfer('a')).not.toThrow();
    expect(listTransfers()[0]?.phase).toBe('cancelled');
  });

  it('clears settled transfers but leaves running ones', () => {
    add('a');
    add('b');
    settleTransfer('a', 'done');
    clearSettledTransfers();
    expect(listTransfers().map(t => t.id)).toEqual(['b']);
  });

  it('caps how many finished transfers are kept', () => {
    for (let i = 0; i < 40; i += 1) {
      add(`t${i}`);
      settleTransfer(`t${i}`, 'done');
    }
    expect(listTransfers().length).toBeLessThanOrEqual(20);
  });
});

describe('picking a stopped transfer back up', () => {
  /**
   * A resumable transfer continues from what already moved. Starting over
   * would re-send bytes the far end already has, which on a large file is the
   * difference between seconds and minutes.
   */
  it('resumes from the transferred offset', () => {
    const seen: number[] = [];
    registerTransfer(
      {
        id: 'r1',
        direction: 'download',
        path: '/remote/big',
        name: 'big',
        hostId: 'host-1',
        total: 1000,
        resumable: true,
      },
      () => {},
      from => seen.push(from),
    );
    advanceTransfer('r1', 400);
    settleTransfer('r1', 'error', 'network went away');

    retryTransfer('r1');

    expect(seen).toEqual([400]);
  });

  /** Without resume support the same action restarts from zero. */
  it('restarts a non-resumable transfer from the beginning', () => {
    const seen: number[] = [];
    registerTransfer(
      {
        id: 'r2',
        direction: 'download',
        path: '/remote/big',
        name: 'big',
        hostId: 'host-1',
        total: 1000,
        resumable: false,
      },
      () => {},
      from => seen.push(from),
    );
    advanceTransfer('r2', 400);
    settleTransfer('r2', 'cancelled');

    retryTransfer('r2');

    expect(seen).toEqual([0]);
  });

  /** The abandoned attempt is dropped, so it cannot sit beside its retry. */
  it('removes the old record when it is picked back up', () => {
    registerTransfer(
      {
        id: 'r3',
        direction: 'upload',
        path: '/p',
        name: 'p',
        hostId: 'h',
        total: 10,
      },
      () => {},
      () => {},
    );
    settleTransfer('r3', 'error', 'failed');

    retryTransfer('r3');

    expect(listTransfers().some(t => t.id === 'r3')).toBe(false);
  });

  it('does not restart a transfer that is still running', () => {
    const seen: number[] = [];
    registerTransfer(
      {
        id: 'r4',
        direction: 'upload',
        path: '/p',
        name: 'p',
        hostId: 'h',
        total: 10,
      },
      () => {},
      from => seen.push(from),
    );

    retryTransfer('r4');

    expect(seen).toEqual([]);
    expect(canRetryTransfer('r4')).toBe(false);
  });

  it('offers no retry for a backend that registered none', () => {
    registerTransfer(
      {
        id: 'r5',
        direction: 'upload',
        path: '/p',
        name: 'p',
        hostId: 'h',
        total: 10,
      },
      () => {},
    );
    settleTransfer('r5', 'error', 'failed');

    expect(canRetryTransfer('r5')).toBe(false);
    retryTransfer('r5');
    expect(listTransfers().some(t => t.id === 'r5')).toBe(true);
  });

  it('offers a retry once a registered transfer has stopped', () => {
    registerTransfer(
      {
        id: 'r6',
        direction: 'upload',
        path: '/p',
        name: 'p',
        hostId: 'h',
        total: 10,
      },
      () => {},
      () => {},
    );
    expect(canRetryTransfer('r6')).toBe(false);
    settleTransfer('r6', 'error', 'failed');
    expect(canRetryTransfer('r6')).toBe(true);
  });

  /** Clearing finished rows must not leave the restart closures behind. */
  it('drops the restart when the finished rows are cleared', () => {
    registerTransfer(
      {
        id: 'r7',
        direction: 'upload',
        path: '/p',
        name: 'p',
        hostId: 'h',
        total: 10,
      },
      () => {},
      () => {},
    );
    settleTransfer('r7', 'done');
    clearSettledTransfers();

    expect(canRetryTransfer('r7')).toBe(false);
  });
});
