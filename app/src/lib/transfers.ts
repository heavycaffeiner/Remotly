// Process-wide file transfers.
//
// A transfer outlives the screen that started it, so the user can leave the
// browser, open a terminal, and come back to a finished download. That is why
// this sits outside the React tree: a hook's cleanup runs on unmount, which is
// exactly when an upload must not be abandoned.
//
// Screens subscribe for rendering. Nothing about a running transfer depends on
// anyone watching.

import NativeFileIO from '../specs/NativeRemotlyFileIO';

export type TransferDirection = 'upload' | 'download';

export type TransferPhase = 'active' | 'done' | 'error' | 'cancelled';

export interface TransferRecord {
  id: string;
  direction: TransferDirection;
  /** Remote path, for a download the source and for an upload the target. */
  path: string;
  /** Basename, for display. */
  name: string;
  /** Which host it is running against, so the sheet can group by host. */
  hostId: string;
  phase: TransferPhase;
  transferred: number;
  /** Total bytes, or -1 when the size is not known up front. */
  total: number;
  error?: string;
  startedAt: number;
  endedAt?: number;
  /**
   * True when this transfer can be picked up from where it stopped.
   *
   * Set from the backend's capabilities when it is registered, so the sheet
   * offers Resume only where continuing actually keeps the bytes already
   * moved. Where it is false the same button restarts from zero and is
   * labelled Retry, which is the honest description of what happens.
   */
  resumable?: boolean;
}

type Listener = (list: readonly TransferRecord[]) => void;

const records = new Map<string, TransferRecord>();
const listeners = new Set<Listener>();
const cancels = new Map<string, () => void>();

/**
 * How to pick a stopped transfer back up, by id.
 *
 * Held here rather than on the record because it closes over the backend and
 * the local file handle, neither of which belongs in a serialisable snapshot.
 * Registered only for transfers whose backend can restart them.
 */
const restarts = new Map<string, (fromOffset: number) => void>();

/**
 * Keeps the foreground service up while anything is running.
 *
 * Both backends register here, so this is the one place that knows whether the
 * app has work that must survive being backgrounded. Without it Android is
 * free to stop the process's threads once the user leaves, and a transfer dies
 * partway with nothing having failed.
 *
 * Only the transition is reported, so a burst of transfers costs one bridge
 * call rather than one per transfer.
 */
let serviceActive = false;

function syncBackgroundService(): void {
  const running = [...records.values()].some(r => r.phase === 'active');
  if (running === serviceActive) return;
  serviceActive = running;
  void NativeFileIO.setTransfersActive(running).catch(() => {
    // The transfer still runs; it just loses the guarantee. Nothing here is
    // worth failing a transfer over.
  });
}

/** Finished transfers kept for the sheet before the oldest is dropped. */
const SETTLED_CAP = 20;

function snapshot(): readonly TransferRecord[] {
  return [...records.values()].sort((a, b) => b.startedAt - a.startedAt);
}

function emit(): void {
  // Every state change flows through here, so this is where the service and
  // the record set cannot drift apart.
  syncBackgroundService();
  const list = snapshot();
  for (const l of listeners) l(list);
}

/** Drops the oldest settled transfers so the list cannot grow without bound. */
function trim(): void {
  const settled = [...records.values()]
    .filter(r => r.phase !== 'active')
    .sort((a, b) => (a.endedAt ?? 0) - (b.endedAt ?? 0));
  const excess = settled.length - SETTLED_CAP;
  for (let i = 0; i < excess; i += 1) {
    const victim = settled[i];
    if (victim !== undefined) {
      records.delete(victim.id);
      restarts.delete(victim.id);
    }
  }
}

export function subscribeTransfers(listener: Listener): () => void {
  listeners.add(listener);
  listener(snapshot());
  return () => {
    listeners.delete(listener);
  };
}

export function listTransfers(): readonly TransferRecord[] {
  return snapshot();
}

/** Transfers still running. The badge counts these. */
export function activeTransfers(): readonly TransferRecord[] {
  return snapshot().filter(r => r.phase === 'active');
}

/**
 * True when a transfer record would put the app-wide bar on screen.
 *
 * The bar floats over every screen, so anything else pinned to the bottom
 * edge has to know it is there or the two draw on top of each other. Both the
 * indicator's own visibility check and the toast's offset run through this,
 * so they cannot disagree about whether the bar is up.
 */
export function raisesTransferBar(record: TransferRecord): boolean {
  return record.phase === 'active' || record.phase === 'error';
}

/** True when the app-wide transfer bar is on screen. */
export function transferBarVisible(): boolean {
  return snapshot().some(raisesTransferBar);
}

export function registerTransfer(
  record: Omit<TransferRecord, 'phase' | 'transferred' | 'startedAt'>,
  cancel: () => void,
  restart?: (fromOffset: number) => void,
): void {
  records.set(record.id, {
    ...record,
    phase: 'active',
    transferred: 0,
    startedAt: Date.now(),
  });
  cancels.set(record.id, cancel);
  if (restart !== undefined) restarts.set(record.id, restart);
  emit();
}

/**
 * Picks a failed or cancelled transfer back up.
 *
 * Resumable transfers continue from what already moved; the rest start over.
 * The record is dropped here and the backend registers a fresh one, so the
 * sheet never shows the abandoned attempt beside its replacement.
 *
 * A no-op for a transfer that is still running or cannot be restarted.
 */
export function retryTransfer(id: string): void {
  const r = records.get(id);
  const restart = restarts.get(id);
  if (r === undefined || restart === undefined || r.phase === 'active') return;
  const from = r.resumable === true ? r.transferred : 0;
  records.delete(id);
  restarts.delete(id);
  cancels.delete(id);
  emit();
  restart(from);
}

/** True when a stopped transfer offers a way to pick it back up. */
export function canRetryTransfer(id: string): boolean {
  const r = records.get(id);
  return r !== undefined && r.phase !== 'active' && restarts.has(id);
}

export function advanceTransfer(id: string, transferred: number): void {
  const r = records.get(id);
  if (r === undefined || r.phase !== 'active') return;
  records.set(id, { ...r, transferred });
  emit();
}

export function settleTransfer(
  id: string,
  phase: Exclude<TransferPhase, 'active'>,
  error?: string,
): void {
  const r = records.get(id);
  if (r === undefined) return;
  records.set(id, {
    ...r,
    phase,
    ...(error === undefined ? {} : { error }),
    endedAt: Date.now(),
  });
  cancels.delete(id);
  trim();
  emit();
}

/** Asks a running transfer to stop. Settling is left to its own callback. */
export function cancelTransfer(id: string): void {
  const cancel = cancels.get(id);
  if (cancel === undefined) return;
  cancels.delete(id);
  try {
    cancel();
  } catch {
    // A cancel that throws must not strand the record as active.
  }
  settleTransfer(id, 'cancelled');
}

/** Clears settled transfers from the list. Running ones are left alone. */
export function clearSettledTransfers(): void {
  for (const [id, r] of records) {
    if (r.phase !== 'active') {
      records.delete(id);
      // The closure holds the backend and a file handle, so dropping the row
      // without it would keep both alive for the life of the process.
      restarts.delete(id);
    }
  }
  emit();
}

/** Test seam: drops everything, running or not. */
export function resetTransfers(): void {
  records.clear();
  cancels.clear();
  // Restart closures hold a backend and a file handle. Leaving them behind
  // kept both alive after everything they belonged to was dropped, and a
  // later id collision would resume a transfer this reset ended.
  restarts.clear();
  emit();
}
