import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/**
 * Buffered output must reach the terminal after a reconnect.
 *
 * useWorkspaceConnection holds output in `pending` until the viewport can take
 * it, and `readyRef` is what says it can. The native view reports ready once
 * per mount, and the viewport is keyed by session and retry attempt, neither
 * of which changes when the channel drops. So anything that clears `readyRef`
 * on a channel or connection event turns it off for the life of the screen:
 * every later write is buffered instead of drawn and the terminal stays blank
 * through the reconnect.
 *
 * Readiness describes the mounted viewport, not the channel. Only
 * onViewportReady may set it.
 */
describe('workspace viewport readiness', () => {
  const source = readFileSync(
    join(__dirname, '..', 'useWorkspaceConnection.ts'),
    'utf8',
  );

  it('sets readiness only from the viewport ready callback', () => {
    const assignments = source.match(/readyRef\.current\s*=\s*[^;]+;/g) ?? [];

    expect(assignments).toEqual(['readyRef.current = true;']);
  });

  it('never clears readiness on a channel or connection event', () => {
    expect(source).not.toMatch(/readyRef\.current\s*=\s*false/);
  });

  it('holds buffered output until the viewport is ready', () => {
    const flush =
      /const flushPending = useCallback\(\(\) => \{([\s\S]*?)\}, \[\]\);/.exec(
        source,
      );

    expect(flush).not.toBeNull();
    expect(flush?.[1]).toMatch(/if \(!readyRef\.current\) return;/);
  });

  /**
   * Over the cap the oldest chunks go. Clearing the whole queue discarded the
   * screen about to be drawn and left the terminal blank until the next write.
   */
  it('drops the oldest buffered output rather than all of it', () => {
    expect(source).toMatch(/pending\.current\.shift\(\)/);
    expect(source).not.toMatch(
      /pending\.current = \[\];\s*\n\s*pendingBytes\.current = 0;\s*\n\s*\}\s*\n\s*\}\),/,
    );
  });

  /**
   * Locally dropped output leaves the same hole as the daemon's own dropped
   * window, so it has to reach the banner the same way. Setting the flag
   * without republishing the attachment would leave the screen showing the
   * stale track, so both are required.
   */
  it('reports locally dropped output as a gap', () => {
    expect(source).toMatch(/cur\.track\.continuity = 'gap';/);

    const handler = /transport\.onEvent\('termData',([\s\S]*?)\n {6}\}\),/.exec(
      source,
    );

    expect(handler).not.toBeNull();
    expect(handler?.[1]).toMatch(/setActiveAttachment\(/);
  });
});
