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

/**
 * The outer view of each tab, in render order.
 *
 * Tab reports its position from the wrapper it renders around itself, which is
 * what the strip scrolls toward. NativeWind renders each styled View as a pair
 * that both carry the same props, so the matches are taken in twos and only
 * the first of each pair is kept: one entry per tab.
 */
function rows(tree: ReactTestRenderer) {
  const view = strip(tree);
  const all = tree.root.findAll(
    n =>
      typeof n.props?.onLayout === 'function' &&
      n.props.onLayout !== view.props.onLayout &&
      String(n.props.className ?? '').includes('rounded-full'),
  );
  return all.filter((_, i) => i % 2 === 0);
}

/** Reports one tab's measured position, as the platform would after layout. */
function layoutTab(
  tree: ReactTestRenderer,
  index: number,
  x: number,
  width: number,
): void {
  const row = rows(tree)[index];
  act(() => {
    row.props.onLayout({ nativeEvent: { layout: { x, width } } });
  });
}

/** Captures the scroll targets the strip asks for. */
function captureScrolls(tree: ReactTestRenderer): number[] {
  const out: number[] = [];
  const instance = strip(tree).instance as { scrollTo?: unknown } | null;
  if (instance !== null) {
    instance.scrollTo = (o: { x: number }) => out.push(o.x);
  }
  return out;
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

  /**
   * onLayout runs after the effect, so a tab switched to before it has ever
   * been measured has no entry to scroll toward. Giving up there leaves the
   * strip parked on the previous tab with nothing to retry it, which is what
   * happens to a newly created tab.
   */
  it('scrolls to a tab that had not been measured when it was selected', () => {
    const tree = render({ activeSessionId: TABS[0].sessionId });
    measure(tree, { viewport: 300, content: 900 });
    layoutTab(tree, 0, 0, 100);

    // Switch to a tab the strip has never measured.
    act(() => {
      tree.update(
        <SessionTabs
          tabs={TABS}
          activeSessionId={TABS[2].sessionId}
          onSelect={() => undefined}
          onClose={() => undefined}
          onNew={() => undefined}
        />,
      );
    });

    const scrolls = captureScrolls(tree);
    // Its measurement arrives afterwards, which is what has to drive the
    // scroll that the effect could not.
    layoutTab(tree, 2, 700, 100);

    expect(scrolls.length).toBe(1);
    // Centred: 700 + 50 - 150 = 600, inside the 600 range.
    expect(scrolls[0]).toBe(600);
  });

  /**
   * The content width is not known on the first render, so clamping against it
   * then pins every target to zero and the strip never moves.
   */
  it('waits for the content width before deciding where to scroll', () => {
    const tree = render({ activeSessionId: TABS[2].sessionId });
    const view = strip(tree);

    // Viewport known, content not yet: nothing may be decided from this.
    act(() => {
      view.props.onLayout({ nativeEvent: { layout: { width: 300 } } });
    });
    layoutTab(tree, 2, 700, 100);

    const scrolls = captureScrolls(tree);
    act(() => {
      view.props.onContentSizeChange(900, 0);
    });

    // Clamped against a real range rather than against zero.
    expect(scrolls).toEqual([600]);
  });
});
