/**
 * @format
 */

// The swipe that drives an attached multiplexer. It shares the terminal with a
// vertical scroll and a two-finger pinch, so what counts and what is ignored
// is the whole contract here.

import { DOUBLE_TAP_MS, isDoubleTap, muxAction } from '../muxGestures';
import { herdrKeys } from '../muxKeys';

/** A released drag, with the fields the responder reports. */
function swipe(
  partial: Partial<Parameters<typeof muxAction>[0]>,
): Parameters<typeof muxAction>[0] {
  return { fingers: 1, dx: 0, dy: 0, vx: 0, vy: 0, ...partial };
}

describe('a swipe across an attached terminal', () => {
  it("moves between the multiplexer's tabs", () => {
    expect(muxAction(swipe({ dx: -120 }))).toBe('tab-next');
    expect(muxAction(swipe({ dx: 120 }))).toBe('tab-previous');
  });

  // Two fingers are the pinch that sets the font size. A drag cannot be told
  // from a pinch reliably enough to navigate with, so it is left alone.
  it('ignores a drag that ever had a second finger down', () => {
    expect(muxAction(swipe({ fingers: 2, dx: -300 }))).toBeNull();
    expect(muxAction(swipe({ fingers: 2, dy: -300 }))).toBeNull();
  });

  // The terminal scrolls vertically, and a scroll that changed tab would be
  // unusable.
  it('ignores a drag up or down', () => {
    expect(muxAction(swipe({ dy: -200 }))).toBeNull();
  });

  it('ignores a drag that has not travelled', () => {
    expect(muxAction(swipe({ dx: -12 }))).toBeNull();
  });

  // A flick is as deliberate as a long drag, and requiring the distance made
  // the gesture feel unresponsive.
  it('takes a fast flick that did not travel far', () => {
    expect(muxAction(swipe({ dx: -36, vx: -0.9 }))).toBe('tab-next');
  });
});

describe('a double tap on an attached terminal', () => {
  it('takes a second tap that follows closely, in the same place', () => {
    expect(isDoubleTap({ elapsedMs: 120, dx: 4, dy: 6 })).toBe(true);
  });

  // Two taps a moment apart are two taps: one opens the keyboard, and moving
  // the workspace under the user instead would be wrong.
  it('ignores a second tap that came too late', () => {
    expect(isDoubleTap({ elapsedMs: DOUBLE_TAP_MS + 1, dx: 0, dy: 0 })).toBe(
      false,
    );
  });

  // Two taps at opposite ends of the screen were aimed at different things.
  it('ignores a second tap that landed somewhere else', () => {
    expect(isDoubleTap({ elapsedMs: 100, dx: 120, dy: 0 })).toBe(false);
  });

  // Timestamps come from the touch, so a clock that went backwards must not
  // read as a gesture that has not happened yet.
  it('ignores a gap that runs backwards', () => {
    expect(isDoubleTap({ elapsedMs: -1, dx: 0, dy: 0 })).toBe(false);
  });
});

describe('the keys a gesture sends to herdr', () => {
  /** Ctrl+B, herdr's prefix. */
  const PREFIX = 0x02;

  // A workspace move is not here: herdr ships those unbound, so the screen
  // makes them over the socket instead of typing anything.
  it('cycles tabs and panes with the bindings herdr ships', () => {
    expect([...herdrKeys('tab-next')]).toEqual([PREFIX, 0x6e]);
    expect([...herdrKeys('tab-previous')]).toEqual([PREFIX, 0x70]);
    expect([...herdrKeys('pane-next')]).toEqual([PREFIX, 0x09]);
    expect([...herdrKeys('pane-previous')]).toEqual([PREFIX, 0x1b, 0x5b, 0x5a]);
  });
});
