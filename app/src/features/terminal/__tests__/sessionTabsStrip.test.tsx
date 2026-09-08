/**
 * @format
 */

import React from 'react';
import { ScrollView, StyleSheet } from 'react-native';
import { PaperProvider } from 'react-native-paper';
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
 * what the strip scrolls toward. The component and its host element both match,
 * so entries are deduplicated by handler: one per tab.
 */
function rows(tree: ReactTestRenderer) {
  const view = strip(tree);
  const seen = new Set<unknown>();
  return tree.root
    .findAll(n => {
      if (typeof n.props?.onLayout !== 'function') return false;
      if (n.props.onLayout === view.props.onLayout) return false;
      const flat = StyleSheet.flatten(n.props.style);
      return flat?.borderRadius === 999;
    })
    .filter(n => {
      if (seen.has(n.props.onLayout)) return false;
      seen.add(n.props.onLayout);
      return true;
    });
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
      <PaperProvider>
        <SessionTabs
          tabs={TABS}
          activeSessionId={TABS[0].sessionId}
          onSelect={() => undefined}
          onClose={() => undefined}
          onNew={() => undefined}
          {...props}
        />
      </PaperProvider>,
    );
  });
  return tree;
}

/** Every string the tree renders, in order. */
function texts(tree: ReactTestRenderer): string[] {
  const out: string[] = [];
  const walk = (node: unknown): void => {
    if (typeof node === 'string') {
      out.push(node);
      return;
    }
    if (Array.isArray(node)) {
      node.forEach(walk);
      return;
    }
    const el = node as { children?: unknown } | null;
    if (el !== null && typeof el === 'object' && 'children' in el) {
      walk(el.children);
    }
  };
  walk(tree.toJSON());
  return out;
}

describe('SessionTabs with one session', () => {
  const ONE = [TABS[0]];

  /**
   * The bar would repeat what the title already says, and it costs two
   * terminal rows.
   */
  it('draws no bar', () => {
    const tree = render({ tabs: ONE, activeSessionId: ONE[0].sessionId });

    expect(tree.root.findAllByType(ScrollView)).toHaveLength(0);
  });

  /**
   * Renaming is driven from the terminal's menu through renameRequest, and the
   * dialog it opens lives here. Unmounting with the bar left that action dead.
   */
  it('still opens the rename dialog the menu asks for', () => {
    const tree = render({
      tabs: ONE,
      activeSessionId: ONE[0].sessionId,
      onRename: () => undefined,
    });
    expect(texts(tree)).not.toContain('Rename session');

    act(() => {
      tree.update(
        <PaperProvider>
          <SessionTabs
            tabs={ONE}
            activeSessionId={ONE[0].sessionId}
            onSelect={() => undefined}
            onClose={() => undefined}
            onNew={() => undefined}
            onRename={() => undefined}
            renameRequest={1}
          />
        </PaperProvider>,
      );
    });

    expect(texts(tree)).toContain('Rename session');
  });
});

describe('SessionTabs strip', () => {
  /**
   * A horizontal ScrollView in a flex-row sizes to its content unless it is
   * given a flex constraint. Without one the measured width is the content
   * width, the scrollable range computes as zero, and the offset can never be
   * pulled back after a tab closes: the strip keeps scrolling into blank space.
   */
  it('bounds the strip to the row rather than to its content', () => {
    const view = strip(render());
    const flat = StyleSheet.flatten(view.props.style);

    expect(flat?.flex).toBe(1);
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
        <PaperProvider>
          <SessionTabs
            tabs={TABS}
            activeSessionId={TABS[2].sessionId}
            onSelect={() => undefined}
            onClose={() => undefined}
            onNew={() => undefined}
          />
        </PaperProvider>,
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
