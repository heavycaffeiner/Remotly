import {
  shouldClaimSwipe,
  swipeDirection,
  SWIPE_AXIS_RATIO,
  SWIPE_CLAIM_PX,
  SWIPE_COMMIT_PX,
} from '../swipeNav';

describe('shouldClaimSwipe', () => {
  it('ignores a drag that has barely moved', () => {
    expect(shouldClaimSwipe(SWIPE_CLAIM_PX - 1, 0)).toBe(false);
  });

  it('claims a clearly horizontal drag', () => {
    expect(shouldClaimSwipe(SWIPE_CLAIM_PX + 10, 0)).toBe(true);
    expect(shouldClaimSwipe(-(SWIPE_CLAIM_PX + 10), 0)).toBe(true);
  });

  // Vertical drags belong to whatever is being scrolled underneath.
  it('leaves a vertical drag alone', () => {
    expect(shouldClaimSwipe(20, 200)).toBe(false);
  });

  it('needs more horizontal travel than vertical', () => {
    const dy = 30;
    expect(shouldClaimSwipe(dy * SWIPE_AXIS_RATIO - 1, dy)).toBe(false);
    expect(shouldClaimSwipe(dy * SWIPE_AXIS_RATIO + 5, dy)).toBe(true);
  });
});

describe('swipeDirection', () => {
  it('stays put for a short slow drag', () => {
    expect(swipeDirection(10, 0)).toBe(0);
  });

  it('moves forward when dragged left', () => {
    expect(swipeDirection(-(SWIPE_COMMIT_PX + 1), 0)).toBe(1);
  });

  it('moves back when dragged right', () => {
    expect(swipeDirection(SWIPE_COMMIT_PX + 1, 0)).toBe(-1);
  });

  // A quick flick is as deliberate as a long drag, so speed commits on its own.
  it('commits a short fast flick', () => {
    expect(swipeDirection(-(SWIPE_CLAIM_PX + 5), -1.2)).toBe(1);
  });

  it('ignores speed when the drag never really started', () => {
    expect(swipeDirection(2, -3)).toBe(0);
  });
});
