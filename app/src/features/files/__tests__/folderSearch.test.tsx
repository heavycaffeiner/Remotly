/**
 * @format
 */

// Browsing a folder: the listing the user sees, and what a search covers.
//
// The browser used to fetch 500 entries at a time and only asked for the next
// page when the list was scrolled to its end. A search that matched anything
// in the first page therefore left the list short, no page ever followed, and
// the rest of the directory was never searched. An entry past the 500th was
// invisible to the search box with nothing on screen saying so. Navigating
// also blanked the list to a spinner on every step, including a step back to
// a directory that had just been read.

import React from 'react';
import { SafeAreaProvider, type Metrics } from 'react-native-safe-area-context';
import { PaperProvider } from 'react-native-paper';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { act, create, type ReactTestRenderer } from 'react-test-renderer';

import NativeSftp from '../../../specs/NativeRemotlySftp';
import { FilesScreen } from '../FilesScreen';
import { SettingsProvider } from '../../../theme/SettingsProvider';

const METRICS: Metrics = {
  frame: { x: 0, y: 0, width: 400, height: 800 },
  insets: { top: 24, left: 0, right: 0, bottom: 16 },
};

/** A directory big enough that the old pager would have stopped inside it. */
const LISTING = Array.from({ length: 1200 }, (_, i) => ({
  name:
    i === 0
      ? 'sub'
      : i === 900
      ? 'needle.log'
      : `filler-${i.toString().padStart(4, '0')}`,
  isDirectory: i === 0,
  isSymlink: false,
  size: i,
  modifyTimeMillis: 0,
  permissions: i === 0 ? 0o40755 : 0o100644,
}));

const SUBDIR = [
  {
    name: 'inner.txt',
    isDirectory: false,
    isSymlink: false,
    size: 1,
    modifyTimeMillis: 0,
    permissions: 0o100644,
  },
];

/**
 * Presses the row whose accessible name starts with `label`.
 *
 * Several nodes carry the name (the touchable and its host view), so the one
 * with the handler is the one to press.
 */
async function press(tree: ReactTestRenderer, label: string): Promise<void> {
  const node = tree.root.findAll(
    n =>
      typeof n.props.accessibilityLabel === 'string' &&
      n.props.accessibilityLabel.startsWith(label) &&
      typeof n.props.onPress === 'function',
  )[0];
  if (node === undefined) throw new Error(`no pressable "${label}"`);
  const onPress: () => void = node.props.onPress;
  await act(async () => {
    onPress();
  });
}

const Stack = createNativeStackNavigator();

// Unmounted after each test: VirtualizedList schedules a render batch on a
// timer, and one left running past the end of a test fires outside act.
let mounted: ReactTestRenderer | null = null;

async function mount(): Promise<ReactTestRenderer> {
  let tree!: ReactTestRenderer;
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
  // Lets the SFTP status poll and the first listing settle.
  await act(async () => {
    await Promise.resolve();
  });
  mounted = tree;
  return tree;
}

/** Types into the search box the same way the keyboard does. */
async function search(tree: ReactTestRenderer, query: string): Promise<void> {
  const box = tree.root.findAll(
    n => n.props.accessibilityLabel === 'Search this folder',
  );
  await act(async () => {
    box[box.length - 1].props.onChangeText(query);
  });
}

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
    if (node !== null && typeof node === 'object' && 'children' in node) {
      walk(node.children);
    }
  };
  walk(tree.toJSON());
  return out;
}

describe('searching a large folder', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (NativeSftp.status as jest.Mock).mockResolvedValue({ state: 'READY' });
    (NativeSftp.list as jest.Mock).mockResolvedValue({
      entries: JSON.stringify(LISTING),
    });
  });

  afterEach(async () => {
    const tree = mounted;
    mounted = null;
    if (tree !== null) await act(async () => tree.unmount());
  });

  it('finds an entry far past the old page size', async () => {
    const tree = await mount();
    await search(tree, 'needle');
    expect(texts(tree)).toContain('needle.log');
  });

  it('reads the directory once, not once per page', async () => {
    await mount();
    expect((NativeSftp.list as jest.Mock).mock.calls).toHaveLength(1);
  });

  it('reports the match against the whole directory', async () => {
    const tree = await mount();
    await search(tree, 'needle');
    expect(texts(tree)).toContain('1 of 1200 items');
  });

  // Stepping back used to blank the list and show a spinner until the server
  // answered again, even though the directory had just been read.
  it('redraws a directory it has already read before the refetch answers', async () => {
    const list = NativeSftp.list as jest.Mock;
    list.mockImplementation(async (_hostId: string, path: string) => ({
      entries: JSON.stringify(path === '/' ? LISTING : SUBDIR),
    }));
    const tree = await mount();

    await press(tree, 'folder sub');
    await act(async () => {
      await Promise.resolve();
    });
    expect(texts(tree)).toContain('inner.txt');

    // A refetch that never answers, so only the cache can fill the screen.
    const stalled = Promise.withResolvers<{ entries: string }>();
    list.mockImplementation(() => stalled.promise);
    await press(tree, 'Up one level');
    expect(texts(tree)).toContain('1200 items');
  });
});
