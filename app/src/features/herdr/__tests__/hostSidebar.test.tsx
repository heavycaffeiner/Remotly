/**
 * @format
 */

// The sidebar is the app's management surface: a host's workspaces, their tabs,
// and the actions that used to be spread across a screen of its own and a
// seventeen-entry menu. What matters here is that a row acts on its own subject
// and that entering a workspace reports the one that was pressed.

import React from 'react';
import { act, create, type ReactTestRenderer } from 'react-test-renderer';
import { PaperProvider } from 'react-native-paper';
import { SafeAreaProvider, type Metrics } from 'react-native-safe-area-context';

import { encodeBase64String } from '../../../lib/base64';
import { HostSidebar, type HerdrEnterRequest } from '../HostSidebar';
import NativeHerdr from '../../../specs/NativeRemotlyHerdr';

const METRICS: Metrics = {
  frame: { x: 0, y: 0, width: 400, height: 800 },
  insets: { top: 24, left: 0, right: 0, bottom: 16 },
};

const exec = NativeHerdr.exec as jest.MockedFunction<typeof NativeHerdr.exec>;

const SNAPSHOT = JSON.stringify({
  result: {
    snapshot: {
      focused_workspace_id: 'w1',
      focused_tab_id: 'w1:t1',
      workspaces: [
        {
          workspace_id: 'w1',
          label: 'api',
          number: 1,
          tab_count: 2,
          pane_count: 2,
          active_tab_id: 'w1:t1',
          focused: true,
          agent_status: 'unknown',
        },
        {
          workspace_id: 'w2',
          label: 'deploy',
          number: 2,
          tab_count: 0,
          pane_count: 0,
          active_tab_id: null,
          focused: false,
          agent_status: 'unknown',
        },
      ],
      tabs: [
        {
          tab_id: 'w1:t1',
          workspace_id: 'w1',
          label: 'shell',
          number: 1,
          pane_count: 1,
          focused: true,
          agent_status: 'unknown',
        },
        {
          tab_id: 'w1:t9',
          workspace_id: 'w1',
          label: 'logs',
          number: 9,
          pane_count: 1,
          focused: false,
          agent_status: 'unknown',
        },
      ],
      panes: [],
    },
  },
});

const SESSIONS = JSON.stringify({
  sessions: [
    {
      name: 'default',
      default: true,
      running: true,
      session_dir: '/home/dev/.config/herdr',
      socket_path: '/home/dev/.config/herdr/herdr.sock',
    },
  ],
});

function plain(command: string): string {
  return command.replace(/'/g, '');
}

function bridge(): string[] {
  const sent: string[] = [];
  exec.mockImplementation(async (_hostId: string, command: string) => {
    const asked = plain(command);
    sent.push(asked);
    const stdout = asked.includes('api snapshot')
      ? SNAPSHOT
      : asked.includes('session list')
      ? SESSIONS
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

const entered: HerdrEnterRequest[] = [];

/** Mounted permanent, which is the state that draws without a tap to open. */
async function mount(): Promise<ReactTestRenderer> {
  let tree!: ReactTestRenderer;
  await act(async () => {
    tree = create(
      <SafeAreaProvider initialMetrics={METRICS}>
        <PaperProvider>
          <HostSidebar
            hostId="h1"
            hostName="devbox"
            session={null}
            open
            permanent
            currentWorkspaceId="w1"
            onClose={() => undefined}
            onEnterWorkspace={request => entered.push(request)}
            onOpenShells={() => undefined}
            onOpenFiles={() => undefined}
          />
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
    if (node !== null && typeof node === 'object' && 'children' in node) {
      walk(node.children);
    }
  };
  walk(tree.toJSON());
  return out;
}

async function pressLabel(
  tree: ReactTestRenderer,
  label: string | RegExp,
): Promise<void> {
  const matches = (value: unknown): boolean =>
    typeof value === 'string' &&
    (typeof label === 'string' ? value === label : label.test(value));
  const found = tree.root
    .findAll(
      node =>
        matches(node.props.accessibilityLabel) &&
        typeof node.props.onPress === 'function',
      { deep: true },
    )
    .at(0);
  if (found === undefined)
    throw new Error(`no control labelled ${String(label)}`);
  await act(async () => {
    found.props.onPress();
  });
}

beforeEach(() => {
  exec.mockReset();
  entered.length = 0;
});

describe('the host sidebar', () => {
  it('lists the workspaces and the tabs of the one on screen', async () => {
    bridge();
    const tree = await mount();

    const shown = texts(tree);
    expect(shown).toContain('api');
    expect(shown).toContain('deploy');
    // The current workspace starts expanded, which is where the user is.
    expect(shown).toContain('shell');
    expect(shown).toContain('logs');
  });

  it('reports the workspace whose row was entered', async () => {
    bridge();
    const tree = await mount();

    await pressLabel(tree, /Open a terminal on deploy/);

    expect(entered).toEqual([
      { workspaceId: 'w2', label: 'deploy', session: null },
    ]);
  });

  it('focuses the tab whose row was pressed, not the focused one', async () => {
    const sent = bridge();
    const tree = await mount();

    await pressLabel(tree, /Focus tab logs/);

    expect(sent.filter(c => c.includes('tab focus'))).toEqual([
      'herdr tab focus w1:t9',
    ]);
  });

  it('adds a tab to the workspace whose row asked for it', async () => {
    const sent = bridge();
    const tree = await mount();

    await pressLabel(tree, 'New tab in api');

    expect(sent.filter(c => c.includes('tab create'))).toEqual([
      'herdr tab create --workspace w1',
    ]);
  });

  it('says which workspace is showing in words, not by colour alone', async () => {
    bridge();
    const tree = await mount();

    expect(texts(tree)).toContain('showing');
  });
});
