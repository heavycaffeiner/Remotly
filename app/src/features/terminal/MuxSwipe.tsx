// The gesture layer over an attached multiplexer.
//
// Wraps the terminal and claims a drag once it is clearly one of the three
// moves. Claimed with the capture responder because the native terminal view
// handles touches itself; without capturing, the drag never reaches here.

import React, { useMemo, useRef } from 'react';
import { PanResponder, View } from 'react-native';
import { muxAction } from './muxGestures';
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

/** Travel before a two-finger drag is taken, on either axis. */
function claims(fingers: number, dx: number, dy: number): boolean {
  if (fingers >= 2) {
    const far = Math.abs(dx) > SWIPE_CLAIM_PX || Math.abs(dy) > SWIPE_CLAIM_PX;
    return far;
  }
  return (
    Math.abs(dx) > SWIPE_CLAIM_PX &&
    Math.abs(dx) > Math.abs(dy) * SWIPE_AXIS_RATIO
  );
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

  const responder = useMemo(
    () =>
      PanResponder.create({
        onMoveShouldSetPanResponderCapture: (e, g) => {
          if (!live.current.enabled || live.current.disabled) return false;
          fingers.current = Math.max(
            fingers.current,
            e.nativeEvent.touches.length,
          );
          return claims(fingers.current, g.dx, g.dy);
        },
        onPanResponderRelease: (_e, g) => {
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
