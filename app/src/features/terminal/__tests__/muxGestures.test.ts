/**
 * @format
 */

// The swipe that drives an attached multiplexer. It shares the terminal with a
// vertical scroll and a two-finger pinch, so what counts and what is ignored
// is the whole contract here.

import { muxAction } from '../muxGestures';
import { herdrKeys } from '../muxKeys';

/** A released drag, with the fields the responder reports. */
function swipe(
  partial: Partial<Parameters<typeof muxAction>[0]>,
): Parameters<typeof muxAction>[0] {
  return { fingers: 1, dx: 0, dy: 0, vx: 0, vy: 0, ...partial };
}

describe('a swipe across an attached terminal', () => {
  it('moves between workspaces', () => {
    expect(muxAction(swipe({ dx: -120 }))).toBe('workspace-next');
    expect(muxAction(swipe({ dx: 120 }))).toBe('workspace-previous');
  });

  // Two fingers are the pinch that sets the font size. A drag cannot be told
  // from a pinch reliably enough to navigate with, so it is left alone.
  it('ignores a drag that ever had a second finger down', () => {
    expect(muxAction(swipe({ fingers: 2, dx: -300 }))).toBeNull();
    expect(muxAction(swipe({ fingers: 2, dy: -300 }))).toBeNull();
  });

  // The terminal scrolls vertically, and a scroll that moved workspace would
  // be unusable.
  it('ignores a drag up or down', () => {
    expect(muxAction(swipe({ dy: -200 }))).toBeNull();
  });

  it('ignores a drag that has not travelled', () => {
    expect(muxAction(swipe({ dx: -12 }))).toBeNull();
  });

  // A flick is as deliberate as a long drag, and requiring the distance made
  // the gesture feel unresponsive.
  it('takes a fast flick that did not travel far', () => {
    expect(muxAction(swipe({ dx: -36, vx: -0.9 }))).toBe('workspace-next');
  });
});

describe('the keys a gesture sends to herdr', () => {
  /** Ctrl+B, herdr's prefix. */
  const PREFIX = 0x02;

  // herdr ships next_workspace and previous_workspace unbound, so a workspace
  // move goes through the picker: open, move the selection, confirm.
  it('drives the picker for a workspace, which has no binding', () => {
    expect([...herdrKeys('workspace-next')]).toEqual([
      PREFIX,
      0x77,
      0x1b,
      0x5b,
      0x42,
      0x0d,
    ]);
    expect([...herdrKeys('workspace-previous')]).toEqual([
      PREFIX,
      0x77,
      0x1b,
      0x5b,
      0x41,
      0x0d,
    ]);
  });

  // The menu still moves tabs and panes, with the bindings herdr ships.
  it('cycles tabs and panes with the bindings herdr ships', () => {
    expect([...herdrKeys('tab-next')]).toEqual([PREFIX, 0x6e]);
    expect([...herdrKeys('tab-previous')]).toEqual([PREFIX, 0x70]);
    expect([...herdrKeys('pane-next')]).toEqual([PREFIX, 0x09]);
    expect([...herdrKeys('pane-previous')]).toEqual([PREFIX, 0x1b, 0x5b, 0x5a]);
  });
});
