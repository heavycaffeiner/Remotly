/**
 * @format
 */

// A direct transfer can finish before the screen learns its id.
//
// The backend registers its event sink inside the start call and immediately
// replays anything the emitter already held for that id, so a small file can
// reach its terminal event while the screen is still awaiting. Recording the
// outcome against a null id dropped it, and the transfer that had already
// finished sat in the transfer sheet as running for the rest of the session.

import React from 'react';
import { SafeAreaProvider, type Metrics } from 'react-native-safe-area-context';
import { PaperProvider } from 'react-native-paper';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { act, create, type ReactTestRenderer } from 'react-test-renderer';

import NativeSftp from '../../../specs/NativeRemotlySftp';
import NativeFileIO from '../../../specs/NativeRemotlyFileIO';
import { FilesScreen } from '../FilesScreen';
import { SettingsProvider } from '../../../theme/SettingsProvider';
import { activeTransfers, resetTransfers } from '../../../lib/transfers';
import type { PickedFile } from '../../../lib/fileIO';

const METRICS: Metrics = {
  frame: { x: 0, y: 0, width: 400, height: 800 },
  insets: { top: 24, left: 0, right: 0, bottom: 16 },
};

const Stack = createNativeStackNavigator();

/** The screen's own handler for the file-picked event. */
function pickHandler(): (payload: unknown) => void {
  const emitter = NativeFileIO.onPicked as unknown as jest.Mock;
  const calls = emitter.mock.calls;
  return calls[calls.length - 1][0];
}

/** Pushes one event through the shared transfer emitter. */
function emitTransfer(event: Record<string, unknown>): void {
  const emitter = NativeSftp.onTransfer as unknown as jest.Mock;
  for (const call of emitter.mock.calls) call[0](event);
}

const PICKED: PickedFile = {
  uri: 'content://src/small.txt',
  name: 'small.txt',
  size: 12,
  mode: 'upload',
};

describe('a direct upload that finishes before its id arrives', () => {
  let tree: ReactTestRenderer | null = null;

  beforeEach(() => {
    jest.clearAllMocks();
    resetTransfers();
    (NativeSftp.status as jest.Mock).mockResolvedValue({ state: 'READY' });
    (NativeSftp.list as jest.Mock).mockResolvedValue({ entries: '[]' });
  });

  afterEach(async () => {
    const t = tree;
    tree = null;
    if (t !== null) await act(async () => t.unmount());
  });

  it('settles instead of sitting in the sheet as running', async () => {
    // The terminal event lands while the start call is still in flight, so
    // the backend holds it and replays it the moment its sink exists.
    (NativeSftp.startUploadFromUri as jest.Mock).mockImplementation(
      async () => {
        emitTransfer({ id: 'up-fast', offset: 12, done: 12 });
        return 'up-fast';
      },
    );

    await act(async () => {
      tree = create(
        <SafeAreaProvider initialMetrics={METRICS}>
          <PaperProvider>
            <SettingsProvider>
              <NavigationContainer>
                <Stack.Navigator screenOptions={{ headerShown: false }}>
                  <Stack.Screen
                    name="Files"
                    component={FilesScreen}
                    initialParams={{ hostId: 'h1' }}
                  />
                </Stack.Navigator>
              </NavigationContainer>
            </SettingsProvider>
          </PaperProvider>
        </SafeAreaProvider>,
      );
    });
    await act(async () => {
      await Promise.resolve();
    });

    await act(async () => {
      pickHandler()(PICKED);
      await Promise.resolve();
    });
    await act(async () => {
      await Promise.resolve();
    });

    expect(NativeSftp.startUploadFromUri).toHaveBeenCalled();
    expect(activeTransfers().map(t => t.id)).toEqual([]);
  });
});
