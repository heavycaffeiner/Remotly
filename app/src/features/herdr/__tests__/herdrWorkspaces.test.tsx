/**
 * @format
 */

// The populated path of the workspaces screen. No herdr server is reachable in
// a test, so the bridge is driven from fixtures: what matters is that the
// snapshot reaches the list, and that the row actions send the command for the
// workspace the user acted on rather than for whichever one had focus.

import React from 'react';
import { act, create, type ReactTestRenderer } from 'react-test-renderer';
import { PaperProvider } from 'react-native-paper';
import { SafeAreaProvider, type Metrics } from 'react-native-safe-area-context';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';

import { encodeBase64String } from '../../../lib/base64';
import { HerdrWorkspacesScreen } from '../HerdrWorkspacesScreen';
import NativeHerdr from '../../../specs/NativeRemotlyHerdr';
import { sshHostState } from '../../../lib/sshSessions';

const METRICS: Metrics = {
  frame: { x: 0, y: 0, width: 400, height: 800 },
  insets: { top: 24, left: 0, right: 0, bottom: 16 },
};

const exec = NativeHerdr.exec as jest.MockedFunction<typeof NativeHerdr.exec>;

const SESSIONS = JSON.stringify({
  sessions: [
    {
      name: 'default',
      default: true,
      running: true,
      session_dir: '/run/herdr',
      socket_path: '/run/herdr/sock',
    },
  ],
});

const SNAPSHOT = JSON.stringify({
  id: 'cli:api:snapshot',
  result: {
    type: 'session_snapshot',
    snapshot: {
      focused_workspace_id: 'w2',
      focused_tab_id: 'w2:t1',
      focused_pane_id: 'w2:p1',
      tabs: [
        {
          tab_id: 'w2:t1',
          workspace_id: 'w2',
          label: 'shell',
          number: 1,
          pane_count: 2,
          focused: true,
        },
        {
          tab_id: 'w2:t2',
          workspace_id: 'w2',
          label: 'logs',
          number: 2,
          pane_count: 1,
          focused: false,
        },
        {
          tab_id: 'w9:t1',
          workspace_id: 'w9',
          label: 'scratch',
          number: 1,
          pane_count: 1,
          focused: false,
        },
      ],
      panes: [],
      workspaces: [
        {
          workspace_id: 'w2',
          label: 'Remotly',
          number: 1,
          tab_count: 2,
          pane_count: 3,
          active_tab_id: 'w2:t1',
          focused: true,
        },
        {
          workspace_id: 'w9',
          label: 'Scratch',
          number: 2,
          tab_count: 1,
          pane_count: 1,
          active_tab_id: 'w9:t1',
          focused: false,
        },
      ],
      protocol: 20,
      version: '0.8.2',
    },
  },
});

/** Every argument is shell-quoted, so match against the unquoted form. */
function plain(command: string): string {
  return command.replace(/'/g, '');
}

/** Answers each command from a fixture, and records what was asked. */
function bridge(): string[] {
  const sent: string[] = [];
  exec.mockImplementation(async (_hostId: string, command: string) => {
    const asked = plain(command);
    sent.push(asked);
    const stdout = asked.includes('session list')
      ? SESSIONS
      : asked.includes('api snapshot')
      ? SNAPSHOT
      : '';
    return {
      ok: true,
      exitCode: 0,
      stdout: encodeBase64String(stdout),
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
          <NavigationContainer>
            <Stack.Navigator screenOptions={{ headerShown: false }}>
              <Stack.Screen
                name="HerdrWorkspaces"
                component={HerdrWorkspacesScreen}
                initialParams={{ hostId: 'h1', hostName: 'devbox' }}
              />
            </Stack.Navigator>
          </NavigationContainer>
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
      walk(node.children);
    }
  };
  walk(tree.toJSON());
  return out;
}

/** Presses the first control announced under `label`. */
async function pressLabel(
  tree: ReactTestRenderer,
  label: string,
): Promise<void> {
  const node = tree.root.find(
    n =>
      n.props?.accessibilityLabel === label &&
      typeof n.props?.onPress === 'function',
  );
  await act(async () => {
    node.props.onPress();
  });
}

/** Presses the first control whose whole label is `content`. */
async function pressText(
  tree: ReactTestRenderer,
  content: string,
): Promise<void> {
  const node = tree.root.findAll(
    n =>
      n.props?.children === content && typeof n.props?.onPress === 'function',
  )[0];
  await act(async () => {
    node.props.onPress();
  });
}

/** Types into the field announced under `label`. */
async function typeInto(
  tree: ReactTestRenderer,
  label: string,
  value: string,
): Promise<void> {
  const node = tree.root.findAll(
    n =>
      n.props?.accessibilityLabel === label &&
      typeof n.props?.onChangeText === 'function',
  )[0];
  await act(async () => {
    node.props.onChangeText(value);
  });
}

beforeEach(() => {
  exec.mockReset();
});

describe('HerdrWorkspacesScreen', () => {
  it('lists the workspaces the snapshot reported', async () => {
    bridge();

    const tree = await mount();
    const shown = texts(tree);

    expect(shown).toContain('Remotly');
    expect(shown).toContain('Scratch');
    // Counts carry their noun, and a single one is not "1 tabs".
    expect(shown).toContain('2 tabs, 3 panes');
    expect(shown).toContain('1 tab, 1 pane');
  });

  /** Focus is offered only where it would change something. */
  it('offers focus on the unfocused workspace only', async () => {
    bridge();

    const tree = await mount();

    expect(
      tree.root.findAll(n => n.props?.accessibilityLabel === 'Focus Scratch'),
    ).not.toHaveLength(0);
    expect(
      tree.root.findAll(n => n.props?.accessibilityLabel === 'Focus Remotly'),
    ).toHaveLength(0);
  });

  it('focuses the workspace whose row was pressed', async () => {
    const sent = bridge();

    const tree = await mount();
    await pressLabel(tree, 'Focus Scratch');

    expect(sent.some(c => c.includes('workspace focus w9'))).toBe(true);
    expect(sent.some(c => c.includes('workspace focus w2'))).toBe(false);
  });

  /** Closing is destructive, so it goes through a confirmation first. */
  it('closes only after the confirmation is accepted', async () => {
    const sent = bridge();

    const tree = await mount();
    await pressLabel(tree, 'Close Scratch');

    expect(sent.some(c => c.includes('workspace close'))).toBe(false);

    await pressText(tree, 'Close');

    expect(sent.some(c => c.includes('workspace close w9'))).toBe(true);
  });

  it('reports a herdr failure instead of an empty list', async () => {
    exec.mockResolvedValue({
      ok: false,
      exitCode: 0,
      stdout: '',
      stderr: '',
      code: 'ssh_connect_failed',
      message: 'connection refused',
    });

    const tree = await mount();

    expect(texts(tree)).toContain('Could not reach herdr');
  });

  /**
   * A host whose key was never accepted cannot be reached by a one-shot exec
   * at all, so the screen has to name the terminal rather than offer a retry
   * that can only fail again.
   */
  it('sends the user to the terminal when the host key is unaccepted', async () => {
    exec.mockResolvedValue({
      ok: false,
      exitCode: 0,
      stdout: '',
      stderr: '',
      code: 'ssh_host_key_rejected',
      message: 'ssh: handshake failed: host key rejected',
    });

    const tree = await mount();
    const shown = texts(tree);

    expect(shown).toContain('Accept this host key first');
    expect(shown).not.toContain('Could not reach herdr');
    // The remedy is offered as an action, not only described.
    expect(
      tree.root.findAll(
        n =>
          n.props?.children === 'Open terminal' &&
          typeof n.props?.onPress === 'function',
      ),
    ).not.toHaveLength(0);
  });

  /** Tabs stay collapsed until asked for: a phone list has no room for all. */
  it('lists the tabs of a workspace only once it is expanded', async () => {
    bridge();

    const tree = await mount();

    expect(texts(tree)).not.toContain('shell');

    await pressLabel(tree, 'Show tabs in Remotly');

    const shown = texts(tree);
    expect(shown).toContain('shell');
    expect(shown).toContain('logs');
    // Only its own tabs, not every tab in the session.
    expect(shown).not.toContain('scratch');
  });

  it('focuses the tab whose row was pressed', async () => {
    const sent = bridge();

    const tree = await mount();
    await pressLabel(tree, 'Show tabs in Remotly');
    await pressLabel(tree, 'Focus tab logs');

    expect(sent.some(c => c.includes('tab focus w2:t2'))).toBe(true);
  });

  /** A new tab belongs to the workspace it was asked for, not the focused one. */
  it('creates a tab in the workspace it was asked from', async () => {
    const sent = bridge();

    const tree = await mount();
    await pressLabel(tree, 'Show tabs in Remotly');
    await pressLabel(tree, 'New tab in Remotly');
    await pressText(tree, 'Create');

    expect(sent.some(c => c.includes('tab create --workspace w2'))).toBe(true);
  });

  it('renames a workspace under the label that was entered', async () => {
    const sent = bridge();

    const tree = await mount();
    await pressLabel(tree, 'Rename Remotly');
    await typeInto(tree, 'Label', 'Renamed');
    await pressText(tree, 'Rename');

    expect(sent.some(c => c.includes('workspace rename w2 Renamed'))).toBe(
      true,
    );
  });

  it('closes a tab only after the confirmation is accepted', async () => {
    const sent = bridge();

    const tree = await mount();
    await pressLabel(tree, 'Show tabs in Remotly');
    await pressLabel(tree, 'Close tab logs');

    expect(sent.some(c => c.includes('tab close'))).toBe(false);

    await pressText(tree, 'Close');

    expect(sent.some(c => c.includes('tab close w2:t2'))).toBe(true);
  });
});

// A session's focused workspace is session state, not per client, so a second
// attached terminal could only mirror the first. Opening one from two
// different cards has to move the one terminal instead of stacking them.
describe('attaching a terminal', () => {
  it('keeps one attached tab per session, whichever card asked', async () => {
    const sent = bridge();
    const tree = await mount();

    await pressLabel(tree, 'Open a terminal on Remotly');
    await pressLabel(tree, 'Open a terminal on Scratch');

    const tabs = sshHostState('h1').tabs;
    expect(tabs).toHaveLength(1);
    expect(tabs[0].title).toBe('herdr');
    expect(sent.filter(c => c.includes('workspace focus'))).toEqual([
      'herdr workspace focus w2',
      'herdr workspace focus w9',
    ]);
  });
});
