/**
 * @format
 */

import React from 'react';
import { act, create, type ReactTestRenderer } from 'react-test-renderer';

import { NewSessionSheet } from '../NewSessionSheet';

/**
 * Taps the "Shell" row.
 *
 * Found by accessibility label: Pressable renders as a plain View here, so
 * the label is what identifies the row rather than its component type.
 */
function pressShell(tree: ReactTestRenderer): void {
  const row = tree.root.find(
    n =>
      n.props.accessibilityLabel === 'Shell' && n.props.onPress !== undefined,
  );
  act(() => {
    row.props.onPress();
  });
}

describe('NewSessionSheet', () => {
  // The sheet stays mounted across open and close, so anything it latches on
  // the first tap is still latched on the next one. A local "already tapped"
  // flag meant only one session could ever be created: every later tap was
  // dropped before it reached onCreate.
  it('starts a session on every open, not just the first', () => {
    const onCreate = jest.fn();
    let tree!: ReactTestRenderer;
    act(() => {
      tree = create(
        <NewSessionSheet
          visible
          presets={[]}
          busy={false}
          onDismiss={() => undefined}
          onCreate={onCreate}
        />,
      );
    });

    pressShell(tree);
    expect(onCreate).toHaveBeenCalledTimes(1);

    // The picker closes while the session is created, then opens again.
    act(() => {
      tree.update(
        <NewSessionSheet
          visible={false}
          presets={[]}
          busy={false}
          onDismiss={() => undefined}
          onCreate={onCreate}
        />,
      );
    });
    act(() => {
      tree.update(
        <NewSessionSheet
          visible
          presets={[]}
          busy={false}
          onDismiss={() => undefined}
          onCreate={onCreate}
        />,
      );
    });

    pressShell(tree);
    expect(onCreate).toHaveBeenCalledTimes(2);

    pressShell(tree);
    expect(onCreate).toHaveBeenCalledTimes(3);
  });

  // While a create is in flight the screen holds busy, which is what stops a
  // second tap landing on the same session.
  it('ignores a tap while a create is in flight', () => {
    const onCreate = jest.fn();
    let tree!: ReactTestRenderer;
    act(() => {
      tree = create(
        <NewSessionSheet
          visible
          presets={[]}
          busy
          onDismiss={() => undefined}
          onCreate={onCreate}
        />,
      );
    });

    pressShell(tree);
    expect(onCreate).not.toHaveBeenCalled();
  });
});
