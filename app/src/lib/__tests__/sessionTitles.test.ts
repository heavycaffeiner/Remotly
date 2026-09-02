import {
  addSshTab,
  createSshTabs,
  findSshTab,
  setSshTabTitle,
  type SshTabsState,
} from '../sshTabs';

const SID = 'ssh-1';

function withTab(): SshTabsState {
  const { state } = addSshTab(createSshTabs('host-1'), SID, 'Shell 1');
  return state;
}

function titleOf(state: SshTabsState): string {
  return findSshTab(state, SID)?.title ?? '';
}

// A shell repaints its title on every prompt, usually with the working
// directory. The tab has to keep following that rather than freezing on the
// first title it ever sees.
describe('terminal titles', () => {
  it('keeps tracking the program title', () => {
    let state = withTab();
    for (const want of ['~', '~/src', '~/src/remotly']) {
      state = setSshTabTitle(state, SID, want);
      expect(titleOf(state)).toBe(want);
    }
  });

  it('ignores a blank title rather than clearing the label', () => {
    const state = withTab();
    expect(setSshTabTitle(state, SID, '   ')).toBe(state);
    expect(titleOf(state)).toBe('Shell 1');
  });
});

describe('renaming a tab', () => {
  it('pins the name against later program titles', () => {
    let state = setSshTabTitle(withTab(), SID, '~/src');
    state = setSshTabTitle(state, SID, 'build logs', true);
    expect(titleOf(state)).toBe('build logs');

    // The shell's next prompt must not take the name back.
    const after = setSshTabTitle(state, SID, '~/other');
    expect(after).toBe(state);
    expect(titleOf(after)).toBe('build logs');
  });

  it('still allows a second rename', () => {
    let state = setSshTabTitle(withTab(), SID, 'first', true);
    state = setSshTabTitle(state, SID, 'second', true);
    expect(titleOf(state)).toBe('second');
  });

  it('leaves the state alone when nothing changes', () => {
    const state = setSshTabTitle(withTab(), SID, 'named', true);
    expect(setSshTabTitle(state, SID, 'named', true)).toBe(state);
  });
});
