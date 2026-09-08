/**
 * @format
 */

// Paper's Dialog carries no keyboard handling, and a Portal renders it outside
// the screen tree, so nothing an ancestor does can lift it. Every dialog in the
// app that holds a text field (rename a session, name a folder, create a
// workspace) depends on the dialog reading the keyboard itself.

import React from 'react';
import { Keyboard } from 'react-native';
import { Dialog as PaperDialog, PaperProvider } from 'react-native-paper';
import { SafeAreaProvider, type Metrics } from 'react-native-safe-area-context';
import { act, create, type ReactTestRenderer } from 'react-test-renderer';

import { Dialog, DialogContent } from '../ui/dialog';
import { Input } from '../ui/input';

const METRICS: Metrics = {
  frame: { x: 0, y: 0, width: 400, height: 800 },
  insets: { top: 24, left: 0, right: 0, bottom: 16 },
};

function render(): ReactTestRenderer {
  let tree!: ReactTestRenderer;
  act(() => {
    tree = create(
      <SafeAreaProvider initialMetrics={METRICS}>
        <PaperProvider>
          <Dialog open onClose={() => {}}>
            <DialogContent>
              <Input value="" onChangeText={() => {}} />
            </DialogContent>
          </Dialog>
        </PaperProvider>
      </SafeAreaProvider>,
    );
  });
  return tree;
}

/** The bottom margin Paper merges into the dialog's own box. */
function bottomMargin(tree: ReactTestRenderer): number {
  const style = tree.root.findByType(PaperDialog).props.style as
    | { marginBottom?: number }
    | undefined;
  return style?.marginBottom ?? 0;
}

/** Drives the IME directly: the native keyboard module never fires in Jest. */
function showKeyboard(height: number): void {
  const listeners = (
    Keyboard.addListener as unknown as jest.Mock
  ).mock.calls.filter(([event]: [string]) => event === 'keyboardDidShow');
  act(() => {
    for (const [, handler] of listeners) {
      handler({ endCoordinates: { height } });
    }
  });
}

beforeEach(() => {
  jest.spyOn(Keyboard, 'addListener');
});

afterEach(() => {
  jest.restoreAllMocks();
});

describe('Dialog', () => {
  it('sits centred while no keyboard is up', () => {
    expect(bottomMargin(render())).toBe(0);
  });

  it('clears the keyboard once it opens', () => {
    const tree = render();

    showKeyboard(320);

    expect(bottomMargin(tree)).toBe(320);
  });
});
