// A transient message pinned to the bottom of its container.
//
// Auto-dismisses, and announces itself politely rather than interrupting. It
// carries no action: anything the user must act on belongs in a Notice, which
// stays put.

import * as React from 'react';
import { Animated, Easing } from 'react-native';
import { useKeyboardHeight } from './KeyboardLifted';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Surface, useTheme } from 'react-native-paper';
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
  const { colors } = useTheme();
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

  // Shown for a moment after the message clears, so it fades out instead of
  // being deleted mid-sentence.
  const [visible, setVisible] = React.useState(message !== '');
  const enter = React.useRef(new Animated.Value(0)).current;

  React.useEffect(() => {
    if (message !== '') {
      setVisible(true);
      Animated.timing(enter, {
        toValue: 1,
        duration: 160,
        easing: Easing.out(Easing.cubic),
        useNativeDriver: true,
      }).start();
      return;
    }
    Animated.timing(enter, {
      toValue: 0,
      duration: 140,
      easing: Easing.in(Easing.cubic),
      useNativeDriver: true,
    }).start(({ finished }) => {
      if (finished) setVisible(false);
    });
  }, [message, enter]);

  React.useEffect(() => {
    if (message === '') return undefined;
    const timer = setTimeout(() => dismissRef.current(), durationMs);
    return () => clearTimeout(timer);
  }, [message, durationMs]);

  if (!visible) return null;

  return (
    <Animated.View
      pointerEvents="none"
      accessibilityLiveRegion="polite"
      style={{
        position: 'absolute',
        left: 16,
        right: 16,
        zIndex: 20,
        opacity: enter,
        transform: [
          {
            translateY: enter.interpolate({
              inputRange: [0, 1],
              outputRange: [12, 0],
            }),
          },
        ],
        bottom:
          insets.bottom +
          16 +
          keyboard +
          (barVisible ? TRANSFER_BAR_CLEARANCE : 0),
      }}
    >
      <Surface
        elevation={3}
        style={{
          borderRadius: 16,
          paddingHorizontal: 16,
          paddingVertical: 10,
          backgroundColor: colors.inverseSurface as string,
        }}
      >
        <Text
          style={{ color: colors.inverseOnSurface as string }}
          numberOfLines={2}
        >
          {message}
        </Text>
      </Surface>
    </Animated.View>
  );
}
