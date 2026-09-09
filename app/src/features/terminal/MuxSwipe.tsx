// The gesture layer over an attached multiplexer.
//
// Wraps the terminal and claims a one-finger sideways drag, which moves between
// the multiplexer's workspaces. Claimed with the capture responder because the
// native terminal view handles touches itself; without capturing, the drag
// never reaches here.
//
// A second finger down hands the whole gesture back: two fingers are the pinch
// that sets the font size, and a two-finger drag cannot be told from a pinch
// reliably enough to drive navigation with it.

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

  const responder = useMemo(
    () =>
      PanResponder.create({
        // A gesture that is never claimed reports no release and no terminate,
        // so the count is cleared where every gesture begins instead.
        onStartShouldSetPanResponderCapture: e => {
          if (e.nativeEvent.touches.length <= 1) fingers.current = 0;
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
