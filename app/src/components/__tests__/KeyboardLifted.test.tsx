/**
 * @format
 */

import React from 'react';
import { act, create, type ReactTestRenderer } from 'react-test-renderer';
import { Keyboard, View } from 'react-native';

import { KeyboardLifted } from '../KeyboardLifted';

type ShowHandler = (event: { endCoordinates: { screenY: number } }) => void;

let show: ShowHandler | null = null;
let hide: (() => void) | null = null;

beforeEach(() => {
  show = null;
  hide = null;
  jest
    .spyOn(Keyboard, 'addListener')
    .mockImplementation((event: string, cb: unknown) => {
      if (event === 'keyboardDidShow') show = cb as ShowHandler;
      if (event === 'keyboardDidHide') hide = cb as () => void;
      return { remove: jest.fn() } as never;
    });
});

afterEach(() => {
  jest.restoreAllMocks();
});

/** The padding the wrapper applies, which is the whole point of it. */
function padding(tree: ReactTestRenderer): number {
  const root = tree.root.findAllByType(View)[0];
  const style = root?.props.style as { paddingBottom?: number } | undefined;
  return style?.paddingBottom ?? 0;
}

/**
 * Renders with the view placed at a screen position.
 *
 * `top` is what the old implementation could not see: it worked from the
 * height alone, which is only the same thing when the view starts at the top
 * of the screen.
 */
function render(top: number, height: number): ReactTestRenderer {
  let tree: ReactTestRenderer;
  act(() => {
    tree = create(
      <KeyboardLifted>
        <View />
      </KeyboardLifted>,
    );
  });
  const root = tree!.root.findAllByType(View)[0];
  const node = root?.instance as { measureInWindow?: unknown } | null;
  if (node) {
    node.measureInWindow = (
      cb: (x: number, y: number, w: number, h: number) => void,
    ) => cb(0, top, 400, height);
  }
  act(() => {
    root?.props.onLayout({ nativeEvent: { layout: { height } } });
  });
  return tree!;
}

describe('KeyboardLifted', () => {
  it('adds no padding before the keyboard appears', () => {
    expect(padding(render(0, 800))).toBe(0);
  });

  /**
   * The window keeps its full height under edge-to-edge and the IME draws over
   * it, so the field being typed into ends up behind the keyboard. Only the
   * measured overlap is compensated.
   */
  it('pads by how much the keyboard covers', () => {
    const tree = render(0, 800);
    act(() => {
      show?.({ endCoordinates: { screenY: 500 } });
    });

    expect(padding(tree)).toBe(300);
  });

  /**
   * The view usually starts below something: a toolbar, a status bar. Its
   * bottom edge is then top + height, and comparing the height alone against a
   * screen coordinate leaves the padding short by the offset, which is what
   * kept rows under the keyboard.
   */
  it('accounts for where the view starts on screen', () => {
    const tree = render(100, 700);
    act(() => {
      show?.({ endCoordinates: { screenY: 500 } });
    });

    // Bottom edge is 100 + 700 = 800, so the keyboard covers 300.
    expect(padding(tree)).toBe(300);
  });

  /**
   * Where the platform really did resize, the keyboard's top edge sits at or
   * below this view's bottom. Padding then would double the gap.
   */
  it('adds nothing when the window was already resized', () => {
    const tree = render(0, 500);
    act(() => {
      show?.({ endCoordinates: { screenY: 500 } });
    });

    expect(padding(tree)).toBe(0);
  });

  it('adds nothing when the keyboard reports below the view', () => {
    const tree = render(0, 500);
    act(() => {
      show?.({ endCoordinates: { screenY: 900 } });
    });

    expect(padding(tree)).toBe(0);
  });

  it('drops the padding once the keyboard closes', () => {
    const tree = render(0, 800);
    act(() => {
      show?.({ endCoordinates: { screenY: 500 } });
    });
    act(() => {
      hide?.();
    });

    expect(padding(tree)).toBe(0);
  });
});
