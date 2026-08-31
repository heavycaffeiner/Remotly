/**
 * @format
 */

import React from 'react';
import { View } from 'react-native';
import { SafeAreaProvider, type Metrics } from 'react-native-safe-area-context';
import { act, create, type ReactTestRenderer } from 'react-test-renderer';

import { Toast } from '../Toast';
import {
  INDICATOR_HEIGHT,
  TAB_BAR_HEIGHT,
} from '../../features/files/TransferIndicator';
import { registerTransfer, resetTransfers } from '../../lib/transfers';

jest.mock('../../specs/NativeRemotlyFileIO', () => ({
  __esModule: true,
  default: { setTransfersActive: jest.fn().mockResolvedValue(undefined) },
}));

const METRICS: Metrics = {
  frame: { x: 0, y: 0, width: 400, height: 800 },
  insets: { top: 24, left: 0, right: 0, bottom: 16 },
};

/** The toast's own absolute offset from the bottom edge. */
function bottomOffset(tree: ReactTestRenderer): number {
  const node = tree.root.findByType(View);
  const style = node.props.style as { bottom?: number };
  if (typeof style?.bottom !== 'number') {
    throw new Error('toast has no bottom offset');
  }
  return style.bottom;
}

/** Trees created by a test, unmounted after it so nothing outlives Jest. */
const trees: ReactTestRenderer[] = [];

function render(message: string): ReactTestRenderer {
  let tree!: ReactTestRenderer;
  act(() => {
    tree = create(
      <SafeAreaProvider initialMetrics={METRICS}>
        <Toast message={message} onDismiss={() => {}} />
      </SafeAreaProvider>,
    );
  });
  trees.push(tree);
  return tree;
}

/** Puts one running transfer in the store, which is what raises the bar. */
function startTransfer(): void {
  registerTransfer(
    {
      id: 't1',
      direction: 'upload',
      path: '/home/dev/a.txt',
      name: 'a.txt',
      hostId: 'h1',
      total: 100,
    },
    () => {},
  );
}

describe('Toast', () => {
  beforeEach(() => {
    jest.useFakeTimers();
    resetTransfers();
  });

  // The toast subscribes to the transfer store, and settling a transfer asks
  // the native module to drop the foreground service. Left mounted, that call
  // lands after Jest has torn the environment down.
  afterEach(() => {
    act(() => {
      for (const tree of trees.splice(0)) tree.unmount();
      jest.runOnlyPendingTimers();
    });
    jest.useRealTimers();
    resetTransfers();
  });

  /**
   * The transfer bar is mounted by the root navigator and floats over every
   * screen: its bottom edge is at insets.bottom + TAB_BAR_HEIGHT and it grows
   * upward from there. A toast pinned to insets.bottom + 16 lands inside that
   * band and the two draw on top of each other, with no sheet involved.
   */
  it('sits at the bottom edge when no transfer bar is showing', () => {
    expect(bottomOffset(render('Copied'))).toBe(METRICS.insets.bottom + 16);
  });

  it('clears the whole transfer bar while one is running', () => {
    act(() => {
      startTransfer();
    });

    // Above the bar's top edge, not merely above its bottom edge: lifting by
    // the bar's own height alone still left the toast inside it.
    expect(bottomOffset(render('Copied'))).toBeGreaterThanOrEqual(
      METRICS.insets.bottom + TAB_BAR_HEIGHT + INDICATOR_HEIGHT,
    );
  });

  it('drops back to the bottom edge once the transfer bar goes away', () => {
    act(() => {
      startTransfer();
    });

    const tree = render('Copied');

    act(() => {
      resetTransfers();
    });

    expect(bottomOffset(tree)).toBe(METRICS.insets.bottom + 16);
  });

  /**
   * The flag is subscribed, not read once at render. A transfer can start
   * while the toast is already up, and reading module state without a
   * subscription would leave the toast at its old offset, underneath the bar
   * that just appeared.
   */
  it('lifts when a transfer starts while it is already showing', () => {
    const tree = render('Copied');
    expect(bottomOffset(tree)).toBe(METRICS.insets.bottom + 16);

    act(() => {
      startTransfer();
    });

    expect(bottomOffset(tree)).toBeGreaterThanOrEqual(
      METRICS.insets.bottom + TAB_BAR_HEIGHT + INDICATOR_HEIGHT,
    );
  });
});
