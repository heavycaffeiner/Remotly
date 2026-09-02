/**
 * @format
 */

import React from 'react';
import { Keyboard, type KeyboardEvent, View } from 'react-native';
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

/** The keyboard height the fake IME reports. */
const KEYBOARD_HEIGHT = 300;

/** Mirrors the clearance the toast applies for the transfer bar. */
const TRANSFER_BAR_CLEARANCE = TAB_BAR_HEIGHT + INDICATOR_HEIGHT;

type ShowHandler = (event: Pick<KeyboardEvent, 'endCoordinates'>) => void;

/**
 * Installs a fake IME and returns its show and hide triggers.
 *
 * The real listeners come from the native keyboard module, which never fires
 * under Jest, so the events are driven directly.
 */
function fakeKeyboard(): { show: () => void; hide: () => void } {
  let showCb: ShowHandler | null = null;
  let hideCb: (() => void) | null = null;
  jest
    .spyOn(Keyboard, 'addListener')
    .mockImplementation((event: string, cb: unknown) => {
      if (event === 'keyboardDidShow') showCb = cb as ShowHandler;
      if (event === 'keyboardDidHide') hideCb = cb as () => void;
      return { remove: jest.fn() } as never;
    });
  return {
    show: () =>
      act(() => {
        showCb?.({
          endCoordinates: {
            screenX: 0,
            screenY: METRICS.frame.height - KEYBOARD_HEIGHT,
            width: METRICS.frame.width,
            height: KEYBOARD_HEIGHT,
          },
        });
      }),
    hide: () =>
      act(() => {
        hideCb?.();
      }),
  };
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

  afterEach(() => {
    jest.restoreAllMocks();
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

  /**
   * The toast is absolutely positioned, and Yoga resolves a bottom inset
   * against the containing node's size less its border, ignoring padding. The
   * terminal screen lifts its content clear of the IME with paddingBottom, and
   * that padding does not reach the toast, so the zoom notice was drawn behind
   * the keyboard.
   */
  it('clears the keyboard while it is up', () => {
    const keyboard = fakeKeyboard();
    const tree = render('Terminal text: 14 sp');

    keyboard.show();

    expect(bottomOffset(tree)).toBe(
      METRICS.insets.bottom + 16 + KEYBOARD_HEIGHT,
    );
  });

  it('drops back down once the keyboard closes', () => {
    const keyboard = fakeKeyboard();
    const tree = render('Terminal text: 14 sp');

    keyboard.show();
    keyboard.hide();

    expect(bottomOffset(tree)).toBe(METRICS.insets.bottom + 16);
  });

  // Both offsets apply at once: the bar floats above the keyboard, so clearing
  // only the taller of the two would still overlap one of them.
  it('clears the keyboard and the transfer bar together', () => {
    const keyboard = fakeKeyboard();
    act(() => {
      startTransfer();
    });
    const tree = render('Terminal text: 14 sp');

    keyboard.show();

    expect(bottomOffset(tree)).toBe(
      METRICS.insets.bottom + 16 + KEYBOARD_HEIGHT + TRANSFER_BAR_CLEARANCE,
    );
  });
});
