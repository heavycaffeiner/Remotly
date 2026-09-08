import { describe, it, expect } from '@jest/globals';
import { shellQuote, joinShell } from '../shell';
import {
  sessionListCommand,
  workspaceCreateCommand,
  workspaceFocusCommand,
  apiSnapshotCommand,
  paneReadCommand,
  agentPromptCommand,
  parseSessions,
  parseSnapshot,
  parseCreatedWorkspace,
  parseCliError,
  HerdrError,
  parseHerdrLookup,
} from '../herdr';

describe('herdr lookup', () => {
  it('takes the path out of whatever the rc files printed around it', () => {
    expect(
      parseHerdrLookup(
        'p10k: warning\n__remotly_herdr_path__\n/home/d/.local/bin/herdr\n__remotly_herdr_end__\n$ ',
      ),
    ).toBe('/home/d/.local/bin/herdr');
  });

  // `command -v` answers for an alias or a function too, and neither is
  // something the next shell can run.
  it('rejects an answer that is not an absolute path', () => {
    expect(
      parseHerdrLookup(
        '__remotly_herdr_path__\nherdr: aliased to herdr --session work\n__remotly_herdr_end__\n',
      ),
    ).toBeNull();
  });

  it('reads nothing when the shell never got that far', () => {
    expect(parseHerdrLookup('zsh: permission denied\n')).toBeNull();
  });
});
describe('shell quoting', () => {
  it('wraps a plain value in single quotes', () => {
    expect(shellQuote('abc')).toBe("'abc'");
  });

  it('escapes an embedded single quote by closing, escaping, reopening', () => {
    expect(shellQuote("a'b")).toBe("'a'\\''b'");
  });

  it('protects shell metacharacters from expansion', () => {
    expect(shellQuote('$HOME; rm -rf /')).toBe("'$HOME; rm -rf /'");
    expect(shellQuote('a b$(c)')).toBe("'a b$(c)'");
  });

  it('joins every element quoted, including the program', () => {
    expect(
      joinShell(['herdr', 'workspace', 'create', '--label', "O'Brien x"]),
    ).toBe("'herdr' 'workspace' 'create' '--label' 'O'\\''Brien x'");
  });
});

// The exact documents the pinned herdr 0.8.2 CLI prints, captured from a live
// server. A parser that drifts from these shapes is a regression.
const SESSION_LIST =
  '{"sessions":[{"default":true,"name":"default","running":true,"session_dir":"/c/herdr","socket_path":"/c/herdr/herdr.sock"}]}';

const SNAPSHOT =
  '{"id":"cli:api:snapshot","result":{"type":"session_snapshot","snapshot":{"agents":[],"focused_pane_id":"w2:p1","focused_tab_id":"w2:t1","focused_workspace_id":"w2","panes":[{"cwd":"/home/x","focused":true,"pane_id":"w2:p1","tab_id":"w2:t1","terminal_id":"term_1","workspace_id":"w2"}],"protocol":20,"tabs":[{"focused":true,"label":"1","number":1,"pane_count":1,"tab_id":"w2:t1","workspace_id":"w2"}],"version":"0.8.2","workspaces":[{"active_tab_id":"w2:t1","focused":true,"label":"Remotly","number":1,"pane_count":1,"tab_count":1,"workspace_id":"w2"}]}}}';

const CREATED =
  '{"id":"cli:workspace:create","result":{"root_pane":{"focused":false,"pane_id":"w8:p1","tab_id":"w8:t1","workspace_id":"w8"},"tab":{"label":"1","number":1,"pane_count":1,"tab_id":"w8:t1","workspace_id":"w8"},"type":"workspace_created","workspace":{"active_tab_id":"w8:t1","focused":false,"label":"__probe","number":4,"pane_count":1,"tab_count":1,"workspace_id":"w8"}}}';

describe('command builders', () => {
  it('targets the default session with no flag', () => {
    expect(sessionListCommand()).toBe("'herdr' 'session' 'list' '--json'");
  });

  it('places a named session as a global flag before the subcommand', () => {
    expect(workspaceCreateCommand({ label: 'web' }, 'work')).toBe(
      "'herdr' '--session' 'work' 'workspace' 'create' '--label' 'web'",
    );
  });

  it('quotes a user label so a metacharacter cannot run a different command', () => {
    expect(workspaceCreateCommand({ label: 'x; curl evil' })).toBe(
      "'herdr' 'workspace' 'create' '--label' 'x; curl evil'",
    );
  });

  it('builds focus, snapshot, pane-read, and prompt commands', () => {
    expect(workspaceFocusCommand('w2')).toBe(
      "'herdr' 'workspace' 'focus' 'w2'",
    );
    expect(apiSnapshotCommand()).toBe("'herdr' 'api' 'snapshot'");
    expect(paneReadCommand({ paneId: 'w2:p1', lines: 3 })).toBe(
      "'herdr' 'pane' 'read' 'w2:p1' '--lines' '3'",
    );
    expect(agentPromptCommand('w2:p1', 'run tests', true)).toBe(
      "'herdr' 'agent' 'prompt' 'w2:p1' 'run tests' '--wait'",
    );
  });
});

describe('parsing', () => {
  it('parses a bare session list document', () => {
    const sessions = parseSessions(SESSION_LIST);
    expect(sessions).toHaveLength(1);
    expect(sessions[0]).toMatchObject({
      name: 'default',
      default: true,
      running: true,
      socketPath: '/c/herdr/herdr.sock',
    });
  });

  it('parses an api snapshot into the full session state', () => {
    const snap = parseSnapshot(SNAPSHOT);
    expect(snap.focusedWorkspaceId).toBe('w2');
    expect(snap.focusedPaneId).toBe('w2:p1');
    expect(snap.protocol).toBe(20);
    expect(snap.version).toBe('0.8.2');
    expect(snap.workspaces).toHaveLength(1);
    expect(snap.workspaces[0].label).toBe('Remotly');
    expect(snap.panes[0].paneId).toBe('w2:p1');
    expect(snap.panes[0].cwd).toBe('/home/x');
  });

  it('tolerates an omitted snapshot field rather than throwing', () => {
    const sparse = JSON.stringify({
      id: 'cli:api:snapshot',
      result: { type: 'session_snapshot', snapshot: {} },
    });
    expect(parseSnapshot(sparse).workspaces).toEqual([]);
    expect(parseSnapshot(sparse).focusedPaneId).toBeNull();
    expect(parseSnapshot(sparse).protocol).toBeNull();
  });

  it('parses a created workspace, keeping the root pane and tab', () => {
    const created = parseCreatedWorkspace(CREATED);
    expect(created.workspace.workspaceId).toBe('w8');
    expect(created.workspace.label).toBe('__probe');
    expect(created.rootPane?.paneId).toBe('w8:p1');
    expect(created.tab?.tabId).toBe('w8:t1');
  });

  it('surfaces a CLI error document as its code and message', () => {
    const doc =
      '{"error":{"code":"session_stop_failed","message":"session x is not running"}}';
    expect(parseCliError(doc)).toEqual({
      code: 'session_stop_failed',
      message: 'session x is not running',
    });
    expect(parseCliError(SNAPSHOT)).toBeNull();
    expect(parseCliError('not json at all')).toBeNull();
  });

  it('reports non-JSON output as bad_json rather than a raw parse throw', () => {
    expect(() => parseSnapshot('no json here')).toThrow(HerdrError);
    expect(() => parseSessions('[]')).toThrow(HerdrError);
    expect(() => parseSnapshot('no json here')).toThrow(
      'herdr output is not JSON',
    );
  });
});
