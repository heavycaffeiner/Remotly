/**
 * @format
 */

import React from 'react';
import { ScrollView } from 'react-native';
import { act, create, type ReactTestRenderer } from 'react-test-renderer';

import { SessionTabs, type SessionTabView } from '../SessionTabs';

const TABS: SessionTabView[] = [
  { sessionId: 'a'.repeat(16), label: 'one', status: 'live' },
  { sessionId: 'b'.repeat(16), label: 'two', status: 'live' },
  { sessionId: 'c'.repeat(16), label: 'three', status: 'live' },
];

function strip(tree: ReactTestRenderer) {
  return tree.root.findByType(ScrollView);
}

/** Feeds the strip the viewport and content widths it decides against. */
function measure(
  tree: ReactTestRenderer,
  opts: { viewport: number; content: number },
): void {
  const view = strip(tree);
  act(() => {
    view.props.onLayout({ nativeEvent: { layout: { width: opts.viewport } } });
    view.props.onContentSizeChange(opts.content, 0);
  });
}

function render(
  props: Partial<React.ComponentProps<typeof SessionTabs>> = {},
): ReactTestRenderer {
  let tree!: ReactTestRenderer;
  act(() => {
    tree = create(
      <SessionTabs
        tabs={TABS}
        activeSessionId={TABS[0].sessionId}
        onSelect={() => undefined}
        onClose={() => undefined}
        onNew={() => undefined}
        {...props}
      />,
    );
  });
  return tree;
}

describe('SessionTabs strip', () => {
  /**
   * A horizontal ScrollView in a flex-row sizes to its content unless it is
   * given a flex constraint. Without one the measured width is the content
   * width, the scrollable range computes as zero, and the offset can never be
   * pulled back after a tab closes: the strip keeps scrolling into blank space.
   */
  it('bounds the strip to the row rather than to its content', () => {
    const view = strip(render());
    const className = String(view.props.className ?? '');

    expect(className).toMatch(/\bflex-1\b/);
  });

  /** The scrollable range is the difference, so both have to be tracked. */
  it('tracks the viewport and content widths separately', () => {
    const tree = render();
    const view = strip(tree);

    expect(typeof view.props.onLayout).toBe('function');
    expect(typeof view.props.onContentSizeChange).toBe('function');
  });

  /**
   * Closing a tab shrinks the content while the strip keeps the offset it had
   * when the content was wider, which parks it past the last tab.
   */
  it('pulls the offset back when the content no longer reaches it', () => {
    const tree = render();
    const view = strip(tree);
    measure(tree, { viewport: 300, content: 900 });

    act(() => {
      view.props.onScroll({ nativeEvent: { contentOffset: { x: 600 } } });
    });

    const scrolls: number[] = [];
    const instance = view.instance as { scrollTo?: unknown } | null;
    if (instance !== null) {
      instance.scrollTo = (o: { x: number }) => scrolls.push(o.x);
    }

    // Two tabs' worth of content remains: the furthest it can scroll is 300.
    act(() => {
      view.props.onContentSizeChange(600, 0);
    });

    expect(scrolls).toEqual([300]);
  });

  /** An offset inside the range is the user's, and must not be overridden. */
  it('leaves an offset that still fits alone', () => {
    const tree = render();
    const view = strip(tree);
    measure(tree, { viewport: 300, content: 900 });

    act(() => {
      view.props.onScroll({ nativeEvent: { contentOffset: { x: 100 } } });
    });

    const scrolls: number[] = [];
    const instance = view.instance as { scrollTo?: unknown } | null;
    if (instance !== null) {
      instance.scrollTo = (o: { x: number }) => scrolls.push(o.x);
    }

    act(() => {
      view.props.onContentSizeChange(900, 0);
    });

    expect(scrolls).toEqual([]);
  });
});
