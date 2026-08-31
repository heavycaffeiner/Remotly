import {
  MAX_SSH_TABS,
  MAX_SSH_TAB_TITLE,
  addSshTab,
  createSshTabs,
  findSshTab,
  mintSessionId,
  removeSshTab,
  setActiveSshTab,
  setSshTabPhase,
  setSshTabTitle,
} from '../sshTabs';

function withTabs(n: number) {
  let state = createSshTabs('h1');
  for (let i = 1; i <= n; i += 1) {
    state = addSshTab(state, `s${i}`, `Tab ${i}`).state;
  }
  return state;
}

describe('session ids', () => {
  it('only uses characters the native bridge accepts', () => {
    for (let i = 1; i <= 20; i += 1) {
      expect(mintSessionId(i)).toMatch(/^[A-Za-z0-9_-]+$/);
    }
  });

  // The hub builds its map key as `hostId:sessionId`, so a colon inside a
  // session id would make the pair ambiguous.
  it('never contains the hub key separator', () => {
    expect(mintSessionId(1)).not.toContain(':');
  });

  it('survives a nonsense sequence number', () => {
    expect(mintSessionId(0)).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(mintSessionId(-5)).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(mintSessionId(NaN)).toMatch(/^[A-Za-z0-9_-]+$/);
  });
});

describe('adding tabs', () => {
  it('makes the new tab active', () => {
    const { state, tab } = addSshTab(createSshTabs('h1'), 's1', 'Shell');
    expect(tab?.sessionId).toBe('s1');
    expect(state.activeSessionId).toBe('s1');
    expect(state.tabs).toHaveLength(1);
  });

  it('starts a tab in connecting', () => {
    const { tab } = addSshTab(createSshTabs('h1'), 's1', 'Shell');
    expect(tab?.phase).toBe('connecting');
  });

  it('returns the existing tab for a duplicate id', () => {
    const first = addSshTab(createSshTabs('h1'), 's1', 'Shell');
    const second = addSshTab(first.state, 's1', 'Other');
    expect(second.state.tabs).toHaveLength(1);
    expect(second.tab?.title).toBe('Shell');
  });

  it('refuses to exceed the cap', () => {
    const full = withTabs(MAX_SSH_TABS);
    const { state, tab } = addSshTab(full, 'extra', 'Nope');
    expect(tab).toBeNull();
    expect(state.tabs).toHaveLength(MAX_SSH_TABS);
  });

  it('rejects an empty session id', () => {
    const { tab } = addSshTab(createSshTabs('h1'), '', 'Shell');
    expect(tab).toBeNull();
  });
});

describe('removing tabs', () => {
  it('moves focus to the left neighbour', () => {
    const state = withTabs(3);
    const next = removeSshTab(setActiveSshTab(state, 's2'), 's2');
    expect(next.activeSessionId).toBe('s1');
    expect(next.tabs.map(t => t.sessionId)).toEqual(['s1', 's3']);
  });

  it('keeps the active tab when another one closes', () => {
    const state = setActiveSshTab(withTabs(3), 's3');
    const next = removeSshTab(state, 's1');
    expect(next.activeSessionId).toBe('s3');
  });

  it('clears the active id when the last tab closes', () => {
    const next = removeSshTab(withTabs(1), 's1');
    expect(next.tabs).toHaveLength(0);
    expect(next.activeSessionId).toBeNull();
  });

  it('ignores an unknown id', () => {
    const state = withTabs(2);
    expect(removeSshTab(state, 'nope')).toBe(state);
  });

  it('picks the first tab when the leftmost one closes', () => {
    const state = setActiveSshTab(withTabs(3), 's1');
    const next = removeSshTab(state, 's1');
    expect(next.activeSessionId).toBe('s2');
  });
});

describe('phase and title', () => {
  it('records a failure detail', () => {
    const state = setSshTabPhase(withTabs(1), 's1', 'failed', 'auth rejected');
    expect(findSshTab(state, 's1')?.phase).toBe('failed');
    expect(findSshTab(state, 's1')?.detail).toBe('auth rejected');
  });

  it('returns the same object when nothing changes', () => {
    const state = withTabs(1);
    expect(setSshTabPhase(state, 's1', 'connecting', '')).toBe(state);
  });

  it('ignores an unknown id', () => {
    const state = withTabs(1);
    expect(setSshTabPhase(state, 'nope', 'active')).toBe(state);
    expect(setSshTabTitle(state, 'nope', 'x')).toBe(state);
  });

  it('renames a tab', () => {
    const state = setSshTabTitle(withTabs(1), 's1', 'build');
    expect(findSshTab(state, 's1')?.title).toBe('build');
  });

  it('trims surrounding whitespace', () => {
    const state = setSshTabTitle(withTabs(1), 's1', '  build  ');
    expect(findSshTab(state, 's1')?.title).toBe('build');
  });

  // A tab with no label cannot be selected or closed by name.
  it('refuses a blank name', () => {
    const state = withTabs(1);
    expect(setSshTabTitle(state, 's1', '   ')).toBe(state);
    expect(setSshTabTitle(state, 's1', '')).toBe(state);
  });

  it('bounds a long name', () => {
    const state = setSshTabTitle(withTabs(1), 's1', 'x'.repeat(200));
    expect(findSshTab(state, 's1')?.title).toHaveLength(MAX_SSH_TAB_TITLE);
  });

  it('leaves other tabs untouched when renaming', () => {
    const state = setSshTabTitle(withTabs(2), 's1', 'renamed');
    expect(findSshTab(state, 's2')?.title).toBe('Tab 2');
  });

  // One tab failing must not disturb its neighbour.
  it('leaves other tabs untouched', () => {
    const state = setSshTabPhase(withTabs(2), 's1', 'failed', 'gone');
    expect(findSshTab(state, 's2')?.phase).toBe('connecting');
  });
});

describe('activation', () => {
  it('ignores an unknown id', () => {
    const state = withTabs(2);
    expect(setActiveSshTab(state, 'nope')).toBe(state);
  });
});
