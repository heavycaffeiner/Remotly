// Debounce and dedupe for terminal resizes, as a plain object.
//
// The terminal reports a new grid on every layout pass, including the many
// intermediate sizes during a keyboard transition. Forwarding each one hammers
// the remote PTY and can start a resize loop, so identical sizes are dropped
// and the rest are debounced.
//
// This is deliberately not a hook: the rules are pure timer logic and are
// tested directly. useTerminalResize is the React wrapper around it.

export interface GridSize {
  cols: number;
  rows: number;
}

export interface ResizeScheduler {
  /** Records a measured size. Sends it after the debounce window. */
  report(size: GridSize): void;
  /** Sends the latest pending size now, if it differs from the last sent. */
  flush(): void;
  /** Drops any pending resize without sending it. */
  cancel(): void;
  /**
   * Forgets the last-sent baseline as well as any pending size. Used on a
   * session switch: the new session has its own grid, and a size measured for
   * the previous one must never reach it.
   */
  reset(): void;
  /**
   * Clears the baseline if it still records this size.
   *
   * The apply runs asynchronously, so a size can be recorded as sent and then
   * fail to reach the terminal. Clearing it lets the next measurement offer
   * the same size again instead of being deduped against a send that never
   * landed.
   */
  forget(size: GridSize): void;
  /** The last size actually sent. */
  current(): GridSize | null;
}

/**
 * Debounce window.
 *
 * Long enough to outlast a keyboard show or hide animation, which is what
 * produces the burst of intermediate heights. Each size that reaches the PTY
 * makes a full-screen application repaint, so a mid-animation size is a visible
 * redraw for a grid the user never sees.
 */
export const DEFAULT_RESIZE_DELAY_MS = 400;

export function createResizeScheduler(
  send: (size: GridSize) => void,
  delayMs: number = DEFAULT_RESIZE_DELAY_MS,
): ResizeScheduler {
  let timer: ReturnType<typeof setTimeout> | null = null;
  let pending: GridSize | null = null;
  let sent: GridSize | null = null;

  const clearTimer = (): void => {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  };

  const same = (a: GridSize | null, b: GridSize | null): boolean =>
    a !== null && b !== null && a.cols === b.cols && a.rows === b.rows;

  const emit = (): void => {
    clearTimer();
    const next = pending;
    pending = null;
    if (next === null) return;
    if (next.cols <= 0 || next.rows <= 0) return;
    if (same(sent, next)) return;
    // Recorded only once the send has run without throwing. A send that fails
    // has not reached the terminal, and recording it anyway made this drop
    // every later report of the same size: the grid then stayed at the old
    // one with nothing left to correct it. The next measurement re-offers it.
    try {
      send(next);
    } catch {
      return;
    }
    sent = next;
  };

  return {
    report(size: GridSize): void {
      if (same(sent, size)) {
        // Already the live size: drop it without arming a timer.
        pending = null;
        clearTimer();
        return;
      }
      pending = size;
      clearTimer();
      timer = setTimeout(emit, delayMs);
    },
    flush: emit,
    cancel(): void {
      clearTimer();
      pending = null;
    },
    reset(): void {
      clearTimer();
      pending = null;
      sent = null;
    },
    forget(size: GridSize): void {
      if (same(sent, size)) sent = null;
    },
    current: () => sent,
  };
}
