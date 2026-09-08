// Which multiplexer move a released drag asks for.
//
// Three gestures, told apart by finger count and axis: one finger sideways
// moves between tabs, two fingers sideways between panes, two fingers up and
// down between workspaces. Kept apart from the view so the thresholds are
// testable without a touch.

import {
  SWIPE_AXIS_RATIO,
  SWIPE_CLAIM_PX,
  SWIPE_COMMIT_PX,
  SWIPE_COMMIT_VELOCITY,
} from '../../lib/swipeNav';
import type { MuxAction } from './muxKeys';

export interface MuxSwipe {
  /** Fingers down when the drag ended. */
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

/** Whether one axis dominates the other clearly enough to be meant. */
function dominates(along: number, across: number): boolean {
  return Math.abs(along) > Math.abs(across) * SWIPE_AXIS_RATIO;
}

/**
 * The action a drag asks for, or null when it asks for nothing.
 *
 * A drag that is neither clearly sideways nor clearly up and down does
 * nothing: on a terminal the alternative is a scroll being read as a
 * workspace change.
 *
 * Sideways follows the direction a paged view scrolls, where dragging left
 * brings the next thing in. Up and down follows the same reading: dragging up
 * moves forward through the list.
 */
export function muxAction(swipe: MuxSwipe): MuxAction | null {
  const { fingers, dx, dy, vx, vy } = swipe;
  const sideways = dominates(dx, dy) && committed(dx, vx);
  if (fingers >= 2) {
    if (sideways) return dx < 0 ? 'pane-next' : 'pane-previous';
    if (dominates(dy, dx) && committed(dy, vy)) {
      return dy < 0 ? 'workspace-next' : 'workspace-previous';
    }
    return null;
  }
  if (sideways) return dx < 0 ? 'tab-next' : 'tab-previous';
  return null;
}
