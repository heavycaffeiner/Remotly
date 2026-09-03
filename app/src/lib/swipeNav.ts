// Thresholds for the horizontal swipe that moves between tabs.
//
// The gesture competes with vertical scrolling everywhere it is used, and in
// the terminal with a native view that owns vertical drags outright, so it is
// only claimed once it is clearly horizontal. The numbers are shared rather
// than repeated per screen: a swipe that needs a different push depending on
// which tab strip is on screen feels broken.

/** Horizontal travel before the gesture is taken from the child view. */
export const SWIPE_CLAIM_PX = 28;

/** How much more horizontal than vertical the drag has to be to count. */
export const SWIPE_AXIS_RATIO = 2.0;
/** Travel that commits the switch on release. */
export const SWIPE_COMMIT_PX = 44;

/** A flick this fast commits regardless of distance, in px per second. */
export const SWIPE_COMMIT_VELOCITY = 0.3;

/** True when a drag in progress should be claimed as a horizontal swipe. */
export function shouldClaimSwipe(dx: number, dy: number): boolean {
  return (
    Math.abs(dx) > SWIPE_CLAIM_PX &&
    Math.abs(dx) > Math.abs(dy) * SWIPE_AXIS_RATIO
  );
}

/**
 * Which way a released drag should move, or 0 to stay put.
 *
 * Distance or speed is enough on its own: a short flick is as deliberate as a
 * long slow drag, and requiring both makes the gesture feel unresponsive.
 * Negative moves forward, matching the direction a paged view scrolls.
 */
export function swipeDirection(dx: number, vx: number): -1 | 0 | 1 {
  const far = Math.abs(dx) >= SWIPE_COMMIT_PX;
  const fast =
    Math.abs(vx) >= SWIPE_COMMIT_VELOCITY && Math.abs(dx) > SWIPE_CLAIM_PX;
  if (!far && !fast) return 0;
  return dx < 0 ? 1 : -1;
}
