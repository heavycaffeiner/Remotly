/**
 * @format
 */

import React from 'react';
import { act, create, type ReactTestRenderer } from 'react-test-renderer';

import { Empty } from '../States';

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

/** The pressable buttons, in render order. */
function buttons(tree: ReactTestRenderer) {
  return tree.root.findAll(
    node =>
      typeof node.props?.onPress === 'function' &&
      String(
        (node.type as { displayName?: string; name?: string })?.displayName ??
          (node.type as { name?: string })?.name ??
          '',
      ) === 'Button',
  );
}

function render(props: React.ComponentProps<typeof Empty>): ReactTestRenderer {
  let tree: ReactTestRenderer;
  act(() => {
    tree = create(<Empty {...props} />);
  });
  return tree!;
}

describe('Empty', () => {
  /**
   * The hosts list hides its add-host button while empty, so the empty state
   * itself has to carry every way forward. Offering only one stranded a user
   * with no paired daemon: there was no route to the SSH editor at all.
   */
  it('renders both actions when a secondary one is supplied', () => {
    const tree = render({
      icon: 'server-off',
      title: 'No hosts yet',
      action: { label: 'Pair Remotly host', onPress: jest.fn() },
      secondaryAction: { label: 'Add SSH host', onPress: jest.fn() },
    });

    expect(texts(tree)).toEqual(
      expect.arrayContaining(['Pair Remotly host', 'Add SSH host']),
    );
  });

  it('invokes each action from its own button', () => {
    const primary = jest.fn();
    const secondary = jest.fn();
    const tree = render({
      icon: 'server-off',
      title: 'No hosts yet',
      action: { label: 'Pair Remotly host', onPress: primary },
      secondaryAction: { label: 'Add SSH host', onPress: secondary },
    });

    const [first, second] = buttons(tree);
    act(() => {
      first?.props.onPress();
    });
    act(() => {
      second?.props.onPress();
    });

    expect(primary).toHaveBeenCalledTimes(1);
    expect(secondary).toHaveBeenCalledTimes(1);
  });

  it('renders no secondary button when none is supplied', () => {
    const tree = render({
      icon: 'server-off',
      title: 'No hosts yet',
      action: { label: 'Pair Remotly host', onPress: jest.fn() },
    });

    expect(texts(tree)).not.toContain('Add SSH host');
    expect(buttons(tree)).toHaveLength(1);
  });
});
