// A bottom sheet.
//
// Built on RN's Modal, like Dialog, so the Android back button and the
// platform's focus handling apply without reimplementing either. It slides up
// rather than fading, which is what separates it from a dialog: the content
// stays anchored to the bottom edge and the page behind it remains visible.

import * as React from 'react';
import {
  Animated,
  Easing,
  Modal,
  Pressable,
  useWindowDimensions,
  View,
} from 'react-native';
import {
  SafeAreaInsetsContext,
  useSafeAreaInsets,
} from 'react-native-safe-area-context';
import { cn } from '../../lib/utils';
import { Text } from './text';

interface SheetProps {
  open: boolean;
  /** Called for a back-button press or a scrim tap. */
  onClose: () => void;
  /**
   * A transient message shown over the sheet.
   *
   * The screen's own Toast cannot appear above a Modal, so a sheet that
   * reports anything passes the text here instead of rendering one behind it.
   */
  toast?: string;
  children: React.ReactNode;
}

const DURATION_MS = 200;

function Sheet({
  open,
  onClose,
  toast = '',
  children,
}: SheetProps): React.ReactElement {
  const slide = React.useRef(new Animated.Value(0)).current;
  // Read outside the Modal. RN's Modal mounts its children into a separate
  // window, which is not a descendant of the SafeAreaProvider, so a hook read
  // inside it reports zero insets and the sheet's last row ends up behind the
  // gesture bar. The value is captured here and republished below.
  const insets = useSafeAreaInsets();

  // The sheet is measured rather than assumed. A fixed travel distance starts
  // a short sheet off-screen and only catches up at the end, which reads as a
  // jump, and clips one taller than the guess. Seeded with the window height
  // so the very first frame is off-screen rather than flashing in place.
  const { height: windowHeight } = useWindowDimensions();
  const [sheetHeight, setSheetHeight] = React.useState(windowHeight);

  // The Modal is unmounted by `visible`, so closing it on `open` alone cut the
  // exit animation off before its first frame: the sheet vanished instead of
  // sliding down. It stays mounted until the animation reports it has run.
  const [mounted, setMounted] = React.useState(open);

  React.useEffect(() => {
    if (open) setMounted(true);
    Animated.timing(slide, {
      toValue: open ? 1 : 0,
      duration: DURATION_MS,
      easing: open ? Easing.out(Easing.cubic) : Easing.in(Easing.cubic),
      useNativeDriver: true,
    }).start(({ finished }) => {
      if (finished && !open) setMounted(false);
    });
  }, [open, slide]);

  return (
    <Modal
      visible={mounted}
      transparent
      animationType="none"
      statusBarTranslucent
      // Without this the modal window stops above the gesture bar, so the
      // sheet cannot reach the bottom edge and its own inset padding lands in
      // the wrong place. Together with the inset provider below, the sheet
      // draws to the edge and keeps its last row clear of the bar.
      navigationBarTranslucent
      onRequestClose={onClose}
    >
      <SafeAreaInsetsContext.Provider value={insets}>
        <View className="flex-1 justify-end">
          {/* The scrim is a sibling that fills the space above the sheet rather
            than an overlay across the whole modal: an absolutely positioned
            one covers the sheet too and swallows every tap meant for it. */}
          <Animated.View className="flex-1" style={{ opacity: slide }}>
            <Pressable
              className="flex-1 bg-black/50"
              accessibilityLabel="Dismiss"
              onPress={onClose}
            />
          </Animated.View>
          <Animated.View
            onLayout={e => {
              const h = e.nativeEvent.layout.height;
              if (h > 0) setSheetHeight(h);
            }}
            style={{
              transform: [
                {
                  translateY: slide.interpolate({
                    inputRange: [0, 1],
                    outputRange: [sheetHeight, 0],
                  }),
                },
              ],
            }}
          >
            {children}
          </Animated.View>
          {/* Outside the sliding surface, so it holds still while the sheet
              moves and stays above it. */}
          <SheetToast message={toast} />
        </View>
      </SafeAreaInsetsContext.Provider>
    </Modal>
  );
}

function SheetContent({
  className,
  children,
}: {
  className?: string;
  children: React.ReactNode;
}): React.ReactElement {
  const insets = useSafeAreaInsets();
  return (
    <View
      // The gesture bar sits under the sheet's own bottom edge, so the inset is
      // added to the padding rather than replacing it. Without this the last
      // row is drawn behind the navigation bar and cannot be reached.
      style={{ paddingBottom: insets.bottom + 24 }}
      className={cn(
        'max-h-[85%] rounded-t-[28px] bg-card p-5 shadow-2xl',
        className,
      )}
    >
      {/* M3 bottom sheet drag handle pill */}
      <View
        accessibilityElementsHidden
        importantForAccessibility="no-hide-descendants"
        className="mb-4 h-1 w-8 self-center rounded-full bg-outline/40"
      />
      {children}
    </View>
  );
}

/**
 * A toast rendered inside the sheet's own window.
 *
 * The app's Toast is an absolutely positioned view in the screen tree. A Sheet
 * is an RN Modal, which is a separate window drawn over that tree, so no
 * z-index reaches past it: a notice raised while the sheet was open was
 * invisible and its timer expired unseen. Placing it here puts it in the same
 * window as the sheet, above the sheet's own surface.
 *
 * Rendered as a sibling of the sheet surface, pinned to the bottom, so it sits
 * over the sheet rather than scrolling with its content.
 */
function SheetToast({
  message,
}: {
  message: string;
}): React.ReactElement | null {
  const insets = useSafeAreaInsets();
  if (message === '') return null;
  return (
    <View
      pointerEvents="none"
      style={{ bottom: insets.bottom + 16 }}
      className="absolute inset-x-4 rounded-xl bg-foreground px-4 py-3 shadow-lg"
      accessibilityLiveRegion="polite"
    >
      <Text className="text-background" numberOfLines={2}>
        {message}
      </Text>
    </View>
  );
}

function SheetHeader({
  children,
}: {
  children: React.ReactNode;
}): React.ReactElement {
  return <View className="mb-3 gap-1">{children}</View>;
}

function SheetTitle({
  children,
}: {
  children: React.ReactNode;
}): React.ReactElement {
  return (
    <Text
      role="heading"
      className="text-xl font-medium text-foreground tracking-tight"
    >
      {children}
    </Text>
  );
}

export { Sheet, SheetContent, SheetHeader, SheetTitle, SheetToast };
