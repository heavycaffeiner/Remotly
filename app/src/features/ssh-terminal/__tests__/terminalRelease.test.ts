import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Closing an SSH tab must free the terminal that tab was rendering.
 *
 * A native terminal outlives its view so the scrollback survives navigation,
 * and TerminalStore.release is the only thing that frees one. The bridge used
 * to release under `${hostId}:${sessionId}` while the screen bound the view to
 * the bare session id, so the key named a terminal that never existed: every
 * closed tab kept its scrollback allocated until the store's cap evicted it,
 * up to eight terminals of native memory for sessions that were already gone.
 */
describe('ssh terminal release key', () => {
  const module = readFileSync(
    join(
      __dirname,
      '..',
      '..',
      '..',
      '..',
      'android',
      'app',
      'src',
      'main',
      'java',
      'com',
      'remotly',
      'app',
      'bridge',
      'RemotlySshModule.kt',
    ),
    'utf8',
  );

  it('releases the terminal under the bare session id', () => {
    expect(module).toMatch(/TerminalStore\.release\(sessionId\)/);
  });

  it('does not key the release by host and session', () => {
    expect(module).not.toMatch(/TerminalStore\.release\(terminalKey\(/);
    expect(module).not.toMatch(/"\$hostId:\$sessionId"/);
  });
});
