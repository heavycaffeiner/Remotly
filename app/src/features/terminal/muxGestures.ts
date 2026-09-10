// Which multiplexer move a gesture asks for.
//
// Two gestures: a single finger sideways moves between the multiplexer's tabs,
// the strip a terminal user reaches for most, and a double tap moves to the
// next workspace, the coarser step. Two fingers are the terminal's pinch and
// are left alone, since a drag and a pinch cannot be told apart reliably
// enough to share the surface with a font size. Panes are moved from the
// terminal's menu.
//
// Kept apart from the view so the thresholds are testable without a touch.

import {
  SWIPE_AXIS_RATIO,
  SWIPE_CLAIM_PX,
  SWIPE_COMMIT_PX,
  SWIPE_COMMIT_VELOCITY,
} from '../../lib/swipeNav';
import type { MuxAction } from './muxKeys';

export interface MuxSwipe {
  /** Fingers down at any point during the drag. */
  fingers: number;
  dx: number;
  dy: number;
  vx: number;
  vy: number;
}

/** Whether travel or speed along one axis is enough to commit. */
function committed(distance: number, velocity: number): boolean {
  return (
    Math.abs(distance) >= SWIPE_COMMIT_PX ||
    (Math.abs(velocity) >= SWIPE_COMMIT_VELOCITY &&
      Math.abs(distance) > SWIPE_CLAIM_PX)
  );
}

/**
 * The action a drag asks for, or null when it asks for nothing.
 *
 * Sideways follows the direction a paged view scrolls, where dragging left
 * brings the next thing in. A drag that is not clearly sideways does nothing:
 * on a terminal the alternative is a scroll being read as a tab change. A drag
 * that ever had a second finger down does nothing either, because that is the
 * pinch.
 */
export function muxAction(swipe: MuxSwipe): MuxAction | null {
  const { fingers, dx, dy, vx } = swipe;
  if (fingers >= 2) return null;
  const sideways =
    Math.abs(dx) > Math.abs(dy) * SWIPE_AXIS_RATIO && committed(dx, vx);
  if (!sideways) return null;
  return dx < 0 ? 'tab-next' : 'tab-previous';
}

/** How long after a tap a second one still counts as a double tap, in ms. */
export const DOUBLE_TAP_MS = 280;

/** How far apart the two taps may land, in px. */
export const DOUBLE_TAP_SLOP_PX = 40;

/**
 * Whether a second tap follows the first closely enough to be one gesture.
 *
 * Both bounds matter: two taps a second apart are two taps, and two taps at
 * opposite ends of the screen were aimed at different things.
 */
export function isDoubleTap(gap: {
  elapsedMs: number;
  dx: number;
  dy: number;
}): boolean {
  const { elapsedMs, dx, dy } = gap;
  if (elapsedMs < 0 || elapsedMs > DOUBLE_TAP_MS) return false;
  return Math.hypot(dx, dy) <= DOUBLE_TAP_SLOP_PX;
}
