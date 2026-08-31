/**
 * @format
 */

import React from 'react';
import { Modal, Text as RNText } from 'react-native';
import { SafeAreaProvider, type Metrics } from 'react-native-safe-area-context';
import { act, create, type ReactTestRenderer } from 'react-test-renderer';

import { Sheet, SheetContent } from '../ui/sheet';

/** Fixed metrics, so the insets do not depend on a real device. */
const METRICS: Metrics = {
  frame: { x: 0, y: 0, width: 400, height: 800 },
  insets: { top: 24, left: 0, right: 0, bottom: 16 },
};

/** Every string the tree renders, which is what a user can actually read. */
function texts(tree: ReactTestRenderer): string[] {
  const out: string[] = [];
  const walk = (node: unknown): void => {
    if (node == null) return;
    if (typeof node === 'string') {
      out.push(node);
      return;
    }
    if (Array.isArray(node)) {
      node.forEach(walk);
      return;
    }
    walk((node as { children?: unknown }).children);
  };
  walk(tree.toJSON());
  return out;
}

function wrap(el: React.ReactElement): React.ReactElement {
  return <SafeAreaProvider initialMetrics={METRICS}>{el}</SafeAreaProvider>;
}

function render(el: React.ReactElement): ReactTestRenderer {
  let tree!: ReactTestRenderer;
  act(() => {
    tree = create(wrap(el));
  });
  return tree;
}

describe('Sheet', () => {
  // The close animation outlives the test otherwise, and its callback lands
  // after Jest has torn the environment down.
  beforeEach(() => {
    jest.useFakeTimers();
  });

  afterEach(() => {
    act(() => {
      jest.runOnlyPendingTimers();
    });
    jest.useRealTimers();
  });

  /**
   * A Sheet is an RN Modal, which is its own window drawn over the screen
   * tree. A Toast rendered by the screen behind it cannot appear above that
   * window at any z-index, so a notice raised while the sheet was open was
   * invisible and its timer expired unseen. The sheet takes the message and
   * renders it inside its own window instead.
   */
  it('renders a toast inside its own window', () => {
    const tree = render(
      <Sheet open onClose={() => {}} toast="Cancelled a.txt">
        <SheetContent>
          <RNText>body</RNText>
        </SheetContent>
      </Sheet>,
    );

    expect(texts(tree)).toContain('Cancelled a.txt');
  });

  it('renders no toast when there is no message', () => {
    const tree = render(
      <Sheet open onClose={() => {}}>
        <SheetContent>
          <RNText>body</RNText>
        </SheetContent>
      </Sheet>,
    );

    expect(texts(tree)).toEqual(['body']);
  });

  /**
   * The Modal is unmounted by `visible`. Driving it from `open` alone cut the
   * exit animation off before its first frame, so the sheet vanished instead
   * of sliding down. It stays mounted until the animation reports it ran.
   */
  it('keeps the modal mounted while it animates closed', () => {
    let tree!: ReactTestRenderer;
    act(() => {
      tree = create(
        wrap(
          <Sheet open onClose={() => {}}>
            <SheetContent>
              <RNText>body</RNText>
            </SheetContent>
          </Sheet>,
        ),
      );
    });

    expect(tree.root.findByType(Modal).props.visible).toBe(true);

    act(() => {
      tree.update(
        wrap(
          <Sheet open={false} onClose={() => {}}>
            <SheetContent>
              <RNText>body</RNText>
            </SheetContent>
          </Sheet>,
        ),
      );
    });

    // Still mounted: the slide-down has not finished yet.
    expect(tree.root.findByType(Modal).props.visible).toBe(true);
  });
});
