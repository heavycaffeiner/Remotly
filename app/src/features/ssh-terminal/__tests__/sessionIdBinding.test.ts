import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { mintSessionId } from '../../../lib/sshTabs';

/**
 * The native terminal view and the background writer must name the same
 * terminal.
 *
 * lib/sshSessions writes a hidden tab's output through
 * NativeRemotlyTerminalStore.feed(sessionId, ...), and the native view adopts
 * the terminal retained under whatever its `sessionId` prop says. The screen
 * used to pass `${hostId}:${sessionId}`, so the two named different terminals:
 * the view adopted an empty one and the tab's real screen sat in a terminal
 * nobody drew. It looked like rendering lagging a beat and like scrollback
 * that would not refresh.
 *
 * The id is minted with a timestamp, so it is already unique across hosts and
 * needs no prefix to disambiguate it.
 */
describe('ssh terminal session id binding', () => {
  it('mints an id that carries no host prefix', () => {
    const id = mintSessionId(1);

    expect(id).not.toContain(':');
  });

  it('mints distinct ids for distinct tabs', () => {
    const ids = new Set([1, 2, 3, 4].map(mintSessionId));

    expect(ids.size).toBe(4);
  });

  /**
   * Rendering the screen would need the whole navigation and native stack, so
   * the binding is checked at its source: the screen must not build the prop
   * by interpolating the host into the id.
   */
  it('does not prefix the sessionId prop with the host', () => {
    const screen = readFileSync(
      join(__dirname, '..', 'SshTerminalScreen.tsx'),
      'utf8',
    );

    expect(screen).toMatch(/sessionId:\s*state\.activeSessionId/);
    expect(screen).not.toMatch(/sessionId:\s*`\$\{hostId\}/);
  });

  /** The writer side names the terminal by the bare id too. */
  it('feeds the store under the bare session id', () => {
    const sessions = readFileSync(
      join(__dirname, '..', '..', '..', 'lib', 'sshSessions.ts'),
      'utf8',
    );

    expect(sessions).toMatch(/\.feed\(\s*sessionId,/);
  });
});
