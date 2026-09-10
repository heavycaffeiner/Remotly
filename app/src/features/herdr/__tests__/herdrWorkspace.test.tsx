/**
 * @format
 */

// The workspace terminal. No herdr server is reachable in a test, so the
// bridge answers from a fixture: what matters is that the strip is the
// workspace's own tabs and that acting on a chip sends the command for that
// tab rather than for whichever one herdr had focused.

import React from 'react';
import { act, create, type ReactTestRenderer } from 'react-test-renderer';
import { PaperProvider } from 'react-native-paper';
import { SafeAreaProvider, type Metrics } from 'react-native-safe-area-context';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';

import { encodeBase64String } from '../../../lib/base64';
import { HerdrWorkspaceScreen } from '../HerdrWorkspaceScreen';
import NativeHerdr from '../../../specs/NativeRemotlyHerdr';
import { closeSshHost, sshHostState } from '../../../lib/sshSessions';
import { SettingsProvider } from '../../../theme/SettingsProvider';

const METRICS: Metrics = {
  frame: { x: 0, y: 0, width: 400, height: 800 },
  insets: { top: 24, left: 0, right: 0, bottom: 16 },
};

const exec = NativeHerdr.exec as jest.MockedFunction<typeof NativeHerdr.exec>;

const TABS = JSON.stringify({
  id: 'cli:tab:list',
  result: {
    type: 'tab_list',
    tabs: [
      {
        tab_id: 'w2:t1',
        workspace_id: 'w2',
        label: 'shell',
        number: 1,
        pane_count: 2,
        focused: true,
        agent_status: 'unknown',
      },
      {
        tab_id: 'w2:t4',
        workspace_id: 'w2',
        label: 'logs',
        number: 4,
        pane_count: 1,
        focused: false,
        agent_status: 'unknown',
      },
    ],
  },
});

/** Every argument is shell-quoted, so match against the unquoted form. */
function plain(command: string): string {
  return command.replace(/'/g, '');
}

function bridge(): string[] {
  const sent: string[] = [];
  exec.mockImplementation(async (_hostId: string, command: string) => {
    const asked = plain(command);
    sent.push(asked);
    return {
      ok: true,
      exitCode: 0,
      stdout: encodeBase64String(asked.includes('tab list') ? TABS : ''),
      stderr: '',
      code: '',
      message: '',
    };
  });
  return sent;
}

const Stack = createNativeStackNavigator();

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
                  name="HerdrWorkspace"
                  component={HerdrWorkspaceScreen}
                  initialParams={{
                    hostId: 'h1',
                    hostName: 'devbox',
                    workspaceId: 'w2',
                    label: 'Remotly',
                    session: null,
                  }}
                />
              </Stack.Navigator>
            </NavigationContainer>
          </SettingsProvider>
        </PaperProvider>
      </SafeAreaProvider>,
    );
  });
  return tree;
}

function texts(tree: ReactTestRenderer): string[] {
  const out: string[] = [];
  const walk = (node: unknown): void => {
    if (typeof node === 'string') {
      out.push(node);
      return;
    }
    if (Array.isArray(node)) {
      for (const child of node) walk(child);
      return;
    }
    if (node && typeof node === 'object' && 'children' in node) {
      walk((node as { children?: unknown }).children);
    }
  };
  walk(tree.toJSON());
  return out;
}

/** Presses the first control whose accessibility label matches. */
async function pressLabel(
  tree: ReactTestRenderer,
  label: string | RegExp,
): Promise<void> {
  const match = (value: unknown): boolean =>
    typeof value === 'string' &&
    (typeof label === 'string' ? value === label : label.test(value));
  const node = tree.root.findAll(
    n =>
      match(n.props?.accessibilityLabel) &&
      typeof n.props?.onPress === 'function',
  )[0];
  if (node === undefined)
    throw new Error(`no control labelled ${String(label)}`);
  await act(async () => {
    node.props.onPress();
  });
}

beforeEach(() => {
  exec.mockReset();
  closeSshHost('h1');
});

afterEach(() => {
  closeSshHost('h1');
});

describe('a workspace terminal', () => {
  it('focuses the workspace and attaches one terminal to it', async () => {
    const sent = bridge();
    await mount();

    expect(sent.filter(c => c.includes('workspace focus'))).toEqual([
      'herdr workspace focus w2',
    ]);
    const tabs = sshHostState('h1').tabs;
    expect(tabs).toHaveLength(1);
    expect(tabs[0].kind).toBe('workspace');
    expect(tabs[0].workspaceId).toBe('w2');
  });

  it('draws the workspace tabs as the strip', async () => {
    bridge();
    const tree = await mount();

    const shown = texts(tree);
    expect(shown).toContain('shell');
    expect(shown).toContain('logs');
  });

  it('focuses the herdr tab whose chip was pressed', async () => {
    const sent = bridge();
    const tree = await mount();

    await pressLabel(tree, /logs/);

    expect(sent.filter(c => c.includes('tab focus'))).toEqual([
      'herdr tab focus w2:t4',
    ]);
  });

  it('adds a tab to this workspace', async () => {
    const sent = bridge();
    const tree = await mount();

    await pressLabel(tree, 'New session');

    expect(sent.filter(c => c.includes('tab create'))).toEqual([
      'herdr tab create --workspace w2',
    ]);
  });
});
