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

  it('schedules resize when viewport becomes ready', () => {
    expect(source).toMatch(
      /scheduleResizeFor\(h\.id, cur\.sessionId, next\.cols, next\.rows\)/,
    );
  });

  it('tracks terminal data received bytes natively', () => {
    expect(source).toMatch(/cur\.track\.receivedBytes \+= chunkLen;/);
  });

  /**
   * Dropped output leaves a hole in history, so it has to reach the banner.
   * Setting the flag and republishing the attachment ensures the banner updates.
   */
  it('reports replay gap and republishes active attachment', () => {
    expect(source).toMatch(/cur\.track\.continuity = 'gap';/);

    const handler =
      /transport\.onEvent\('replayComplete',([\s\S]*?)\n {6}\}\),/.exec(source);

    expect(handler).not.toBeNull();
    expect(handler?.[1]).toMatch(/setActiveAttachment\(/);
  });

  /**
   * Cursor must be committed immediately on detach to prevent replay duplication
   * when returning to a retained terminal before the periodic timer ticks.
   */
  it('persists cursor on detachActive before clearing active attachment', () => {
    const detach = /const detachActive = useCallback\([\s\S]*?\n {2}\);/.exec(
      source,
    );
    expect(detach).not.toBeNull();
    expect(detach?.[0]).toMatch(
      /commitWs\(setCursor\(w, cur\.sessionId, cursorOf\(cur\.track\)\)\);/,
    );
  });

  it('persists workspace document with updated cursor on unmount cleanup', () => {
    expect(source).toMatch(
      /saveWorkspaceDocument\(hostId, serializeWorkspace\(next\)\)/,
    );
  });
});
