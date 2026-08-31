// A pane that can be swiped left or right to change tab, and animates when it
// does.
//
// The switch is driven by the parent, which owns the tab list, so this only
// reports a direction and plays the transition. The outgoing pane slides out
// and fades, the incoming one slides in from the other side: enough motion to
// show which way the strip moved without delaying the content.
//
// Uses the built-in Animated driver rather than a gesture library, so the
// transform runs on the UI thread with no new dependency.

import React, { useCallback, useEffect, useMemo, useRef } from 'react';
import { Animated, Easing, PanResponder, View } from 'react-native';
import {
  shouldClaimSwipe,
  swipeDirection,
  SWIPE_COMMIT_PX,
} from '../lib/swipeNav';

interface SwipePagerProps {
  /**
   * Identifies what is on screen. A change plays the transition; the sign of
   * the change decides which way, when the parent supplies an index.
   */
  pageKey: string | null;
  /** Position in the strip, used to animate forward and back differently. */
  pageIndex?: number;
  /** Called with -1 for the previous tab and 1 for the next. */
  onSwitch?: ((direction: -1 | 1) => void) | undefined;
  /** Blocks the gesture, for example while a text selection is up. */
  disabled?: boolean;
  children: React.ReactNode;
}

/** How far the incoming pane travels, as a fraction of the drag commit. */
const SLIDE_PX = SWIPE_COMMIT_PX;

const DURATION_MS = 180;

export function SwipePager({
  pageKey,
  pageIndex,
  onSwitch,
  disabled = false,
  children,
}: SwipePagerProps): React.ReactElement {
  const slide = useRef(new Animated.Value(0)).current;
  const fade = useRef(new Animated.Value(1)).current;
  const lastIndex = useRef(pageIndex);
  const lastKey = useRef(pageKey);

  // Read through a ref so the responder is built once: rebuilding it mid-drag
  // drops the gesture.
  const switchRef = useRef(onSwitch);
  switchRef.current = onSwitch;
  const disabledRef = useRef(disabled);
  disabledRef.current = disabled;

  useEffect(() => {
    if (pageKey === lastKey.current) return;
    const before = lastIndex.current;
    lastKey.current = pageKey;
    lastIndex.current = pageIndex;

    // Without an index the direction is unknown, so it fades in place.
    const forward =
      before !== undefined && pageIndex !== undefined
        ? pageIndex > before
        : null;

    slide.setValue(forward === null ? 0 : forward ? SLIDE_PX : -SLIDE_PX);
    fade.setValue(0);
    Animated.parallel([
      Animated.timing(slide, {
        toValue: 0,
        duration: DURATION_MS,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }),
      Animated.timing(fade, {
        toValue: 1,
        duration: DURATION_MS,
        easing: Easing.out(Easing.quad),
        useNativeDriver: true,
      }),
    ]).start();
  }, [pageKey, pageIndex, slide, fade]);

  const responder = useMemo(
    () =>
      PanResponder.create({
        onMoveShouldSetPanResponderCapture: (_e, g) => {
          if (disabledRef.current || switchRef.current === undefined)
            return false;
          return shouldClaimSwipe(g.dx, g.dy);
        },
        onPanResponderRelease: (_e, g) => {
          const direction = swipeDirection(g.dx, g.vx);
          if (direction !== 0) switchRef.current?.(direction);
        },
      }),
    [],
  );

  return (
    <View className="flex-1" {...responder.panHandlers}>
      <Animated.View
        className="flex-1"
        style={{ opacity: fade, transform: [{ translateX: slide }] }}
      >
        {children}
      </Animated.View>
    </View>
  );
}

/**
 * Moves within a tab list, stopping at the ends.
 *
 * Wrapping would let one swipe jump the whole strip, which reads as a bug
 * rather than a shortcut.
 */
export function useTabSwitcher<T>(
  tabs: readonly T[],
  activeId: string | null,
  idOf: (tab: T) => string,
  select: (id: string) => void,
): (direction: -1 | 1) => void {
  return useCallback(
    (direction: -1 | 1) => {
      const index = tabs.findIndex(t => idOf(t) === activeId);
      if (index < 0) return;
      const next = tabs[index + direction];
      if (next === undefined) return;
      select(idOf(next));
    },
    [tabs, activeId, idOf, select],
  );
}
