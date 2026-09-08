/**
 * @format
 */

// The transport half of the herdr layer. What matters to a caller is that a
// failure arrives as a HerdrError it can tell apart: an unreachable host, a
// herdr command that reported its own error, and a command that failed with
// nothing but stderr all lead to different messages on screen.

import { encodeBase64String } from '../base64';
import { HerdrError } from '../herdr';
import { execHerdr, herdrSnapshot } from '../herdrClient';
import NativeHerdr from '../../specs/NativeRemotlyHerdr';

type ExecResult = {
  ok: boolean;
  exitCode: number;
  stdout: string;
  stderr: string;
  code: string;
  message: string;
};

const exec = NativeHerdr.exec as jest.MockedFunction<typeof NativeHerdr.exec>;

/** The bridge's shape, with everything not under test left empty. */
function result(partial: Partial<ExecResult>): ExecResult {
  return {
    ok: true,
    exitCode: 0,
    stdout: '',
    stderr: '',
    code: '',
    message: '',
    ...partial,
  };
}

beforeEach(() => {
  exec.mockReset();
});

describe('execHerdr', () => {
  it('reports a connect failure under the code the bridge gave', async () => {
    exec.mockResolvedValue(
      result({
        ok: false,
        code: 'ssh_auth_failed',
        message: 'The credential was rejected.',
      }),
    );

    await expect(execHerdr('h1', 'herdr api snapshot')).rejects.toMatchObject({
      code: 'ssh_auth_failed',
      detail: 'The credential was rejected.',
    });
  });

  it('names the host when the bridge failed without a code', async () => {
    exec.mockResolvedValue(result({ ok: false }));

    await expect(execHerdr('h1', 'herdr api snapshot')).rejects.toMatchObject({
      code: 'herdr_unreachable',
    });
  });

  /**
   * herdr prints a typed error document to stdout and exits non-zero. Losing
   * its code would leave every herdr-level failure looking the same.
   */
  it("carries herdr's own error code and message", async () => {
    exec.mockResolvedValue(
      result({
        exitCode: 1,
        stdout: encodeBase64String(
          '{"error":{"code":"workspace_not_found","message":"No workspace w9."}}',
        ),
      }),
    );

    await expect(
      execHerdr('h1', 'herdr workspace focus w9'),
    ).rejects.toMatchObject({
      code: 'workspace_not_found',
      detail: 'No workspace w9.',
    });
  });

  /**
   * Which stream carries the error document depends on the command: herdr
   * 0.9.0 answers `session list --json` on stdout but writes `api snapshot`
   * failures to stderr and exits one. Read from stdout alone, a stopped
   * server reached the screen as the raw document printed as prose.
   */
  it('carries an error document that arrived on stderr', async () => {
    exec.mockResolvedValue(
      result({
        exitCode: 1,
        stderr: encodeBase64String(
          '{"id":"cli:api:snapshot","error":{"code":"server_not_running","message":"no herdr server is running at /home/dev/.config/herdr/herdr.sock; run `herdr` to start or attach it"}}',
        ),
      }),
    );

    await expect(execHerdr('h1', 'herdr api snapshot')).rejects.toMatchObject({
      code: 'server_not_running',
      detail:
        'no herdr server is running at /home/dev/.config/herdr/herdr.sock; run `herdr` to start or attach it',
    });
  });

  it('falls back to stderr when the command printed no document', async () => {
    exec.mockResolvedValue(
      result({
        exitCode: 127,
        stderr: encodeBase64String('herdr: command not found\n'),
      }),
    );

    await expect(execHerdr('h1', 'herdr session list')).rejects.toMatchObject({
      code: 'herdr_cli',
      detail: 'herdr: command not found',
    });
  });

  it('reports the exit status when the command said nothing at all', async () => {
    exec.mockResolvedValue(result({ exitCode: 3 }));

    await expect(execHerdr('h1', 'herdr session list')).rejects.toThrow(
      /status 3/,
    );
  });

  it('decodes stdout rather than handing back base64', async () => {
    exec.mockResolvedValue(
      result({ stdout: encodeBase64String('plain output\n') }),
    );

    await expect(execHerdr('h1', 'herdr pane read w1:p1')).resolves.toBe(
      'plain output\n',
    );
  });
});

describe('herdrSnapshot', () => {
  it('returns the session state a screen renders from', async () => {
    exec.mockResolvedValue(
      result({
        stdout: encodeBase64String(
          JSON.stringify({
            id: 'cli:api:snapshot',
            result: {
              type: 'session_snapshot',
              snapshot: {
                agents: [],
                focused_workspace_id: 'w2',
                focused_tab_id: 'w2:t1',
                focused_pane_id: 'w2:p1',
                panes: [],
                tabs: [],
                workspaces: [
                  {
                    workspace_id: 'w2',
                    label: 'Remotly',
                    number: 1,
                    tab_count: 1,
                    pane_count: 1,
                    active_tab_id: 'w2:t1',
                    focused: true,
                  },
                ],
                protocol: 20,
                version: '0.8.2',
              },
            },
          }),
        ),
      }),
    );

    const snap = await herdrSnapshot('h1');

    expect(snap.focusedWorkspaceId).toBe('w2');
    expect(snap.workspaces).toHaveLength(1);
    expect(snap.workspaces[0]).toMatchObject({
      workspaceId: 'w2',
      label: 'Remotly',
      tabCount: 1,
      paneCount: 1,
      focused: true,
    });
  });

  /** A server the app does not understand must degrade, not crash a screen. */
  it('rejects output that is not a herdr document', async () => {
    exec.mockResolvedValue(
      result({ stdout: encodeBase64String('Welcome to Ubuntu\n') }),
    );

    await expect(herdrSnapshot('h1')).rejects.toBeInstanceOf(HerdrError);
  });
});
