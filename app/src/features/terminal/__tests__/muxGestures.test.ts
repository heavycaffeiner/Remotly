/**
 * @format
 */

// The swipes that drive an attached multiplexer. Three gestures share the
// terminal with a scroll and a pinch, so what counts and what is ignored is
// the whole contract here.

import { muxAction, twoFingerIsPinch, twoFingerIsSwipe } from '../muxGestures';
import { herdrKeys } from '../muxKeys';

/** A released drag, with the fields the responder reports. */
function swipe(
  partial: Partial<Parameters<typeof muxAction>[0]>,
): Parameters<typeof muxAction>[0] {
  return { fingers: 1, dx: 0, dy: 0, vx: 0, vy: 0, ...partial };
}

describe('a swipe across an attached terminal', () => {
  it('moves between tabs on one finger', () => {
    expect(muxAction(swipe({ dx: -120 }))).toBe('tab-next');
    expect(muxAction(swipe({ dx: 120 }))).toBe('tab-previous');
  });

  it('moves between panes on two', () => {
    expect(muxAction(swipe({ fingers: 2, dx: -120 }))).toBe('pane-next');
    expect(muxAction(swipe({ fingers: 2, dx: 120 }))).toBe('pane-previous');
  });

  it('moves between workspaces on two, up and down', () => {
    expect(muxAction(swipe({ fingers: 2, dy: -120 }))).toBe('workspace-next');
    expect(muxAction(swipe({ fingers: 2, dy: 120 }))).toBe(
      'workspace-previous',
    );
  });

  // The terminal scrolls vertically under one finger, and a scroll that moved
  // workspace would be unusable.
  it('ignores one finger up and down', () => {
    expect(muxAction(swipe({ dy: -200 }))).toBeNull();
  });

  it('ignores a drag that has not travelled', () => {
    expect(muxAction(swipe({ dx: -12 }))).toBeNull();
    expect(muxAction(swipe({ fingers: 2, dy: -12 }))).toBeNull();
  });

  // A pinch reports as two fingers moving apart, which is diagonal on both.
  it('ignores a drag with no clear axis', () => {
    expect(muxAction(swipe({ fingers: 2, dx: -90, dy: -80 }))).toBeNull();
  });

  // A flick is as deliberate as a long drag, and requiring the distance made
  // the gesture feel unresponsive.
  it('takes a fast flick that did not travel far', () => {
    expect(muxAction(swipe({ fingers: 2, dx: -36, vx: -0.9 }))).toBe(
      'pane-next',
    );
  });
});

// The same rule the terminal applies natively for the pinch. Both sides have
// to read a gesture the same way, or one zooms while the other moves the
// workspace.
describe('telling two fingers travelling from two fingers pinching', () => {
  it('calls fingers moving together a swipe', () => {
    expect(twoFingerIsSwipe(300, 30)).toBe(true);
    expect(twoFingerIsPinch(300, 30)).toBe(false);
  });

  it('calls fingers moving apart a pinch, in either direction', () => {
    expect(twoFingerIsPinch(20, 400)).toBe(true);
    expect(twoFingerIsPinch(20, -400)).toBe(true);
    expect(twoFingerIsSwipe(20, 400)).toBe(false);
  });

  it('calls neither when neither leads', () => {
    expect(twoFingerIsSwipe(120, 100)).toBe(false);
    expect(twoFingerIsPinch(120, 100)).toBe(false);
  });

  it('calls neither before the gesture has gone anywhere', () => {
    expect(twoFingerIsSwipe(10, 2)).toBe(false);
    expect(twoFingerIsPinch(2, 10)).toBe(false);
  });
});
describe('the keys a gesture sends to herdr', () => {
  /** Ctrl+B, herdr's prefix. */
  const PREFIX = 0x02;

  it('cycles tabs and panes with the bindings herdr ships', () => {
    expect([...herdrKeys('tab-next')]).toEqual([PREFIX, 0x6e]);
    expect([...herdrKeys('tab-previous')]).toEqual([PREFIX, 0x70]);
    expect([...herdrKeys('pane-next')]).toEqual([PREFIX, 0x09]);
    expect([...herdrKeys('pane-previous')]).toEqual([PREFIX, 0x1b, 0x5b, 0x5a]);
  });

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
});
