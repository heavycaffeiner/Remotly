import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * The terminal's replies to the running program must reach the session.
 *
 * A shell's startup and every prompt framework query the terminal for its
 * device attributes and colours, and the answer goes back up the pty. The
 * native layer produces those answers and raises onPtyWrite for them, but the
 * screen never passed the prop, so they were dropped: the program waited for a
 * reply that never came and the bytes it had already written appeared on
 * screen as stray characters on first connect.
 *
 * Mouse and wheel reports travel the same callback, so dropping it also meant
 * a tracking application saw no mouse input at all.
 *
 * Checked at the source. Rendering the screen would need the whole navigation
 * and native stack standing up, and the defect is precisely that one prop was
 * absent from the JSX.
 */
describe('pty write wiring', () => {
  const screen = readFileSync(
    join(__dirname, '..', 'TerminalScreen.tsx'),
    'utf8',
  );

  it('passes onPtyWrite to the viewport', () => {
    expect(screen).toMatch(/onPtyWrite=\{handlePtyWrite\}/);
  });

  it('sends those bytes to the session', () => {
    const handler =
      /const handlePtyWrite = useCallback\(([\s\S]*?)\}, \[\]\);/.exec(screen);
    expect(handler).not.toBeNull();
    expect(handler?.[1]).toContain('sendRef.current(bytes)');
  });

  /**
   * A reply is the terminal answering a program, not the user typing, so it
   * must not consume a latched Ctrl or Alt.
   */
  it('does not route replies through the modifier latch', () => {
    const handler =
      /const handlePtyWrite = useCallback\(([\s\S]*?)\}, \[\]\);/.exec(screen);
    expect(handler?.[1]).not.toContain('applyModifier');
  });
});
