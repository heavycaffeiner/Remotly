// React wrapper around the resize scheduler. The scheduling rules live in
// resizeScheduler.ts and are tested there.

import { useEffect, useMemo, useRef } from 'react';
import {
  DEFAULT_RESIZE_DELAY_MS,
  createResizeScheduler,
  type GridSize,
  type ResizeScheduler,
} from './resizeScheduler';

export type { GridSize } from './resizeScheduler';

export interface TerminalResizeOptions {
  /** Sends the size to the session. */
  send: (size: GridSize) => void;
  /** Debounce window in ms. */
  delayMs?: number;
  /**
   * Identifies the session the sizes belong to. Changing it drops the pending
   * resize and the last-sent baseline.
   */
  sessionKey?: string;
}

export type TerminalResizeHandle = ResizeScheduler;

export function useTerminalResize({
  send,
  delayMs = DEFAULT_RESIZE_DELAY_MS,
  sessionKey = '',
}: TerminalResizeOptions): TerminalResizeHandle {
  // The scheduler is created once; the send target is read through a ref so a
  // changed callback does not rebuild it and lose pending state.
  const sendRef = useRef(send);
  sendRef.current = send;

  const scheduler = useMemo(
    () => createResizeScheduler(size => sendRef.current(size), delayMs),
    [delayMs],
  );

  useEffect(() => {
    scheduler.reset();
  }, [sessionKey, scheduler]);

  useEffect(() => () => scheduler.cancel(), [scheduler]);

  return scheduler;
}
