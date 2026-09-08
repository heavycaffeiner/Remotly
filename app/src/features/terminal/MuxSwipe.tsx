// The gesture layer over an attached multiplexer.
//
// Wraps the terminal and claims a drag once it is clearly one of the three
// moves. Claimed with the capture responder because the native terminal view
// handles touches itself; without capturing, the drag never reaches here.
//
// Two fingers are read the same way the terminal reads them for the pinch:
// distance between them against travel of the point between them. Both sides
// have to agree, or a gesture zooms and moves the workspace at once.

import React, { useMemo, useRef } from 'react';
import { PanResponder, View, type GestureResponderEvent } from 'react-native';
import { muxAction, twoFingerIsPinch, twoFingerIsSwipe } from './muxGestures';
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

/** What two fingers turned out to be doing, once it is clear. */
type Verdict = 'unknown' | 'swipe' | 'pinch';

/** The distance between the first two touches, and the point between them. */
function twoFingerShape(
  e: GestureResponderEvent,
): { span: number; x: number; y: number } | null {
  const touches = e.nativeEvent.touches;
  if (touches.length < 2) return null;
  const [a, b] = touches;
  return {
    span: Math.hypot(a.pageX - b.pageX, a.pageY - b.pageY),
    x: (a.pageX + b.pageX) / 2,
    y: (a.pageY + b.pageY) / 2,
  };
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

  // The finger count at the moment of release is one, since the others have
  // lifted by then, so the highest count during the drag is what identifies
  // the gesture.
  const fingers = useRef(0);
  const verdict = useRef<Verdict>('unknown');
  const start = useRef<{ span: number; x: number; y: number } | null>(null);

  const reset = () => {
    fingers.current = 0;
    verdict.current = 'unknown';
    start.current = null;
  };

  const responder = useMemo(
    () =>
      PanResponder.create({
        onMoveShouldSetPanResponderCapture: (e, g) => {
          if (!live.current.enabled || live.current.disabled) return false;
          const shape = twoFingerShape(e);
          if (shape !== null) {
            fingers.current = Math.max(fingers.current, 2);
            if (start.current === null) {
              start.current = shape;
              return false;
            }
            if (verdict.current === 'unknown') {
              const travel = Math.hypot(
                shape.x - start.current.x,
                shape.y - start.current.y,
              );
              const spanChange = shape.span - start.current.span;
              if (twoFingerIsPinch(travel, spanChange)) {
                // Left to the terminal for the rest of the gesture: it is the
                // one that resizes the font.
                verdict.current = 'pinch';
              } else if (twoFingerIsSwipe(travel, spanChange)) {
                verdict.current = 'swipe';
              }
            }
            return verdict.current === 'swipe';
          }
          if (fingers.current >= 2) return verdict.current === 'swipe';
          return (
            Math.abs(g.dx) > SWIPE_CLAIM_PX &&
            Math.abs(g.dx) > Math.abs(g.dy) * SWIPE_AXIS_RATIO
          );
        },
        onPanResponderRelease: (_e, g) => {
          const action = muxAction({
            fingers: fingers.current,
            dx: g.dx,
            dy: g.dy,
            vx: g.vx,
            vy: g.vy,
          });
          const pinched = verdict.current === 'pinch';
          reset();
          if (action !== null && !pinched) live.current.onAction(action);
        },
        onPanResponderTerminate: reset,
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
