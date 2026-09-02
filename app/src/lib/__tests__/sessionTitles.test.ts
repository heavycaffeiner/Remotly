import { isDefaultTitle } from '../workspace';
import { isDefaultSshTitle } from '../sshTabs';

// A program repaints its terminal title constantly: a shell does it on every
// prompt. The title is only adopted while the session still carries the name
// it was created with, so a name the user typed is never overwritten.
describe('isDefaultTitle', () => {
  it('recognises the names the daemon gives a new session', () => {
    expect(isDefaultTitle('shell')).toBe(true);
    expect(isDefaultTitle('agent')).toBe(true);
  });

  it('treats anything else as a chosen name', () => {
    expect(isDefaultTitle('build logs')).toBe(false);
    expect(isDefaultTitle('Shell')).toBe(false);
    expect(isDefaultTitle('')).toBe(false);
    // A preset names the session after itself, which is a chosen name too.
    expect(isDefaultTitle('Fix the tests')).toBe(false);
  });
});

describe('isDefaultSshTitle', () => {
  it('recognises the names the app generates', () => {
    expect(isDefaultSshTitle('Shell')).toBe(true);
    expect(isDefaultSshTitle('Shell 1')).toBe(true);
    expect(isDefaultSshTitle('Shell 12')).toBe(true);
    expect(isDefaultSshTitle('Files')).toBe(true);
    expect(isDefaultSshTitle('Files 3')).toBe(true);
  });

  it('treats anything else as a chosen name', () => {
    expect(isDefaultSshTitle('build logs')).toBe(false);
    expect(isDefaultSshTitle('shell')).toBe(false);
    expect(isDefaultSshTitle('Shell notes')).toBe(false);
    expect(isDefaultSshTitle('My Shell 2')).toBe(false);
    expect(isDefaultSshTitle('')).toBe(false);
  });
});
