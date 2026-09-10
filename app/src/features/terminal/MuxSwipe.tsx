// The gesture layer over an attached multiplexer.
//
// Wraps the terminal and claims two gestures, both with the capture responder
// because the native terminal view handles touches itself: without capturing,
// neither reaches here. A one-finger sideways drag moves between the
// multiplexer's tabs; a double tap moves to the next workspace.
//
// A second finger down hands the whole gesture back: two fingers are the pinch
// that sets the font size, and a two-finger drag cannot be told from a pinch
// reliably enough to drive navigation with it.

import React, { useMemo, useRef } from 'react';
import { PanResponder, View } from 'react-native';
import { isDoubleTap, muxAction } from './muxGestures';
import type { MuxAction } from './muxKeys';
import { SWIPE_AXIS_RATIO, SWIPE_CLAIM_PX } from '../../lib/swipeNav';

interface MuxSwipeProps {
  /** False leaves every touch to the view below. */
  enabled: boolean;
  onAction: (action: MuxAction) => void;
  /** Blocks the gesture, for example while a text selection is up. */
  disabled?: boolean;
  children: React.ReactNode;
}

export function MuxSwipe({
  enabled,
  onAction,
  disabled = false,
  children,
}: MuxSwipeProps): React.ReactElement {
  // Read through refs so the responder is built once: rebuilding it mid-drag
  // drops the gesture.
  const live = useRef({ enabled, disabled, onAction });
  live.current = { enabled, disabled, onAction };

  // The count at release is one, since the other fingers have lifted by then,
  // so the highest count during the drag is what says whether this was a pinch.
  const fingers = useRef(0);

  // Where and when the last tap landed, so the next one can be measured
  // against it. A drag clears it: a tap after a swipe is a first tap.
  const lastTap = useRef({ at: 0, x: 0, y: 0 });

  // Set when a touch was claimed as the second tap of a double tap, so the
  // release knows which gesture it is completing.
  const doubleTap = useRef(false);

  const responder = useMemo(
    () =>
      PanResponder.create({
        onStartShouldSetPanResponderCapture: e => {
          const touch = e.nativeEvent;
          if (touch.touches.length > 1) return false;
          if (!live.current.enabled || live.current.disabled) {
            fingers.current = 0;
            return false;
          }
          // A gesture that is never claimed reports no release and no
          // terminate, so the count is cleared where every gesture begins.
          fingers.current = 0;
          const previous = lastTap.current;
          // The clock is read here rather than taken from the event: a touch
          // start does not always carry a timestamp on Android.
          const now = Date.now();
          const second = isDoubleTap({
            elapsedMs: now - previous.at,
            dx: touch.pageX - previous.x,
            dy: touch.pageY - previous.y,
          });
          if (second) {
            // Claimed, which cancels the touch in the terminal below: the
            // second tap belongs to this gesture, not to the pane under it.
            lastTap.current = { at: 0, x: 0, y: 0 };
            doubleTap.current = true;
            return true;
          }
          lastTap.current = { at: now, x: touch.pageX, y: touch.pageY };
          return false;
        },
        onMoveShouldSetPanResponderCapture: (e, g) => {
          if (!live.current.enabled || live.current.disabled) return false;
          fingers.current = Math.max(
            fingers.current,
            e.nativeEvent.touches.length,
          );
          if (fingers.current >= 2) return false;
          return (
            Math.abs(g.dx) > SWIPE_CLAIM_PX &&
            Math.abs(g.dx) > Math.abs(g.dy) * SWIPE_AXIS_RATIO
          );
        },
        // The capture check stops running once the drag is claimed, so a
        // second finger landing after that would go uncounted and the release
        // would still move a workspace.
        onPanResponderMove: e => {
          fingers.current = Math.max(
            fingers.current,
            e.nativeEvent.touches.length,
          );
          // A drag is not a tap, whichever gesture claimed it.
          if (fingers.current > 0) lastTap.current = { at: 0, x: 0, y: 0 };
        },
        onPanResponderRelease: (_e, g) => {
          if (doubleTap.current) {
            doubleTap.current = false;
            fingers.current = 0;
            live.current.onAction('workspace-next');
            return;
          }
          const action = muxAction({
            fingers: fingers.current,
            dx: g.dx,
            dy: g.dy,
            vx: g.vx,
            vy: g.vy,
          });
          fingers.current = 0;
          if (action !== null) live.current.onAction(action);
        },
        onPanResponderTerminate: () => {
          fingers.current = 0;
          doubleTap.current = false;
        },
      }),
    [],
  );

  if (!enabled) return <View style={{ flex: 1 }}>{children}</View>;
  return (
    <View style={{ flex: 1 }} {...responder.panHandlers}>
      {children}
    </View>
  );
}
