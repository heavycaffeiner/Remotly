// A transient message pinned to the bottom of its container.
//
// Auto-dismisses, and announces itself politely rather than interrupting. It
// carries no action: anything the user must act on belongs in a Notice, which
// stays put.

import * as React from 'react';
import { Keyboard, type KeyboardEvent, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Text } from './ui/text';
import { subscribeTransfers, transferBarVisible } from '../lib/transfers';
import {
  INDICATOR_HEIGHT,
  TAB_BAR_HEIGHT,
} from '../features/files/TransferIndicator';

/**
 * How far to lift the toast while the transfer bar is up.
 *
 * The bar's bottom edge sits at insets.bottom + TAB_BAR_HEIGHT and it grows
 * upward from there, so clearing it means clearing both numbers. Lifting by
 * the bar's height alone left the toast inside it.
 */
const TRANSFER_BAR_CLEARANCE = TAB_BAR_HEIGHT + INDICATOR_HEIGHT;

/**
 * How much of the screen the software keyboard covers, in pixels.
 *
 * The toast is positioned absolutely, and Yoga resolves a bottom inset against
 * the containing node's measured size less its border, never its padding. An
 * ancestor's paddingBottom that lifts the rest of the tree clear of the IME
 * therefore does not move the toast at all, and it ends up drawn behind the
 * keyboard. Measuring the keyboard here is what keeps it visible.
 */
function useKeyboardHeight(): number {
  const [height, setHeight] = React.useState(0);

  React.useEffect(() => {
    const show = Keyboard.addListener('keyboardDidShow', (e: KeyboardEvent) => {
      setHeight(Math.max(0, Math.round(e.endCoordinates.height)));
    });
    const hide = Keyboard.addListener('keyboardDidHide', () => setHeight(0));
    return () => {
      show.remove();
      hide.remove();
    };
  }, []);

  return height;
}

interface ToastProps {
  /** Empty renders nothing. Callers pass state directly. */
  message: string;
  onDismiss: () => void;
  durationMs?: number;
}

export function Toast({
  message,
  onDismiss,
  durationMs = 2000,
}: ToastProps): React.ReactElement | null {
  const insets = useSafeAreaInsets();
  const dismissRef = React.useRef(onDismiss);
  dismissRef.current = onDismiss;

  // The transfer bar is mounted by the root navigator and floats over every
  // screen, so it is not part of any screen's layout and cannot be avoided by
  // ordinary flow. Subscribed rather than read once: a transfer can start or
  // finish while the toast is up, and the toast has to move with it.
  const [barVisible, setBarVisible] = React.useState(transferBarVisible);
  React.useEffect(
    () => subscribeTransfers(() => setBarVisible(transferBarVisible())),
    [],
  );

  // The IME draws over the window under edge-to-edge, so the keyboard's height
  // is added to the offset rather than replacing it.
  const keyboard = useKeyboardHeight();

  React.useEffect(() => {
    if (message === '') return undefined;
    const timer = setTimeout(() => dismissRef.current(), durationMs);
    return () => clearTimeout(timer);
  }, [message, durationMs]);

  if (message === '') return null;

  return (
    <View
      pointerEvents="none"
      style={{
        bottom:
          insets.bottom +
          16 +
          keyboard +
          (barVisible ? TRANSFER_BAR_CLEARANCE : 0),
      }}
      className="absolute inset-x-4 z-20 rounded-md bg-foreground px-4 py-3"
      accessibilityLiveRegion="polite"
    >
      <Text className="text-background" numberOfLines={2}>
        {message}
      </Text>
    </View>
  );
}
