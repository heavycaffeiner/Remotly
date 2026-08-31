import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { MAX_TABS } from '../workspace';
import { MAX_SSH_TABS } from '../sshTabs';

/**
 * The native terminal store must be able to hold every tab a host can open.
 *
 * A terminal outlives its view so the scrollback survives navigation, and
 * TerminalStore evicts the least recently used one past its cap. Only the tab
 * on screen has a renderer, so every other retained terminal is eligible: a
 * cap below the tab limit destroys the scrollback of a session the user still
 * has open and is still running. The store held 8 while a daemon workspace
 * allows 16, so opening a ninth tab silently threw one away.
 */
describe('terminal retention cap', () => {
  const store = readFileSync(
    join(
      __dirname,
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
      'terminal',
      'TerminalStore.kt',
    ),
    'utf8',
  );

  const retained = (): number => {
    const m = /MAX_RETAINED\s*=\s*(\d+)/.exec(store);
    if (m === null) throw new Error('MAX_RETAINED not found');
    return Number(m[1]);
  };

  it('holds at least as many terminals as a host has tabs', () => {
    expect(retained()).toBeGreaterThanOrEqual(Math.max(MAX_TABS, MAX_SSH_TABS));
  });

  it('never evicts a terminal that has a renderer bound', () => {
    expect(store).toMatch(
      /firstOrNull\s*\{\s*!renderers\.containsKey\(it\)\s*\}/,
    );
  });
});
