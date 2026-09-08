// A bottom sheet.
//
// Paper 6 has no BottomSheet, so this is Paper's Portal + Modal (which own the
// scrim, the outside tap, and the Android back button) with a Surface anchored
// to the bottom edge and slid up. Sliding rather than fading is what separates
// it from a dialog: the page behind it stays visible.
//
// The Surface belongs to `Sheet`, not to `SheetContent`: callers put the
// header either side of the content, and a surface owned by the content block
// left the header drawn on the scrim above it.

import * as React from 'react';
import {
  Animated,
  Easing,
  useWindowDimensions,
  View,
  type StyleProp,
  type ViewStyle,
} from 'react-native';
import { Modal, Portal, Surface, useTheme } from 'react-native-paper';
import { SafeAreaInsetsContext } from 'react-native-safe-area-context';
import { Text } from './text';

const ZERO_INSETS = { top: 0, bottom: 0, left: 0, right: 0 };
function useSafeInsets() {
  const ctx = React.useContext(SafeAreaInsetsContext);
  return ctx ?? ZERO_INSETS;
}

interface SheetProps {
  open: boolean;
  /** Called for a back-button press or a scrim tap. */
  onClose: () => void;
  /**
   * A transient message shown over the sheet.
   *
   * The screen's own Toast is covered by the sheet's surface, so a sheet that
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
  const insets = useSafeInsets();
  const { colors } = useTheme();

  // The sheet is measured rather than assumed. A fixed travel distance starts
  // a short sheet off-screen and only catches up at the end, which reads as a
  // jump, and clips one taller than the guess. Seeded with the window height
  // so the very first frame is off-screen rather than flashing in place.
  const { height: windowHeight } = useWindowDimensions();
  const [sheetHeight, setSheetHeight] = React.useState(windowHeight);

  React.useEffect(() => {
    Animated.timing(slide, {
      toValue: open ? 1 : 0,
      duration: DURATION_MS,
      easing: open ? Easing.out(Easing.cubic) : Easing.in(Easing.cubic),
      useNativeDriver: true,
    }).start();
  }, [open, slide]);

  return (
    <Portal>
      <Modal
        visible={open}
        onDismiss={onClose}
        overlayAccessibilityLabel="Dismiss"
        // Paper centers its content and insets the wrapper. The sheet has to
        // reach the bottom edge instead and pads for the gesture bar itself.
        style={{ justifyContent: 'flex-end', marginBottom: 0 }}
        contentContainerStyle={{
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
        <Surface
          elevation={3}
          onLayout={e => {
            const h = e.nativeEvent.layout.height;
            if (h > 0) setSheetHeight(h);
          }}
          // The gesture bar sits under the sheet's own bottom edge, so the
          // inset is added to the padding rather than replacing it. Without
          // this the last row is drawn behind the navigation bar.
          style={{
            maxHeight: '85%',
            borderTopLeftRadius: 28,
            borderTopRightRadius: 28,
            padding: 20,
            paddingBottom: insets.bottom + 24,
          }}
        >
          <View
            accessibilityElementsHidden
            importantForAccessibility="no-hide-descendants"
            style={{
              marginBottom: 16,
              height: 4,
              width: 32,
              alignSelf: 'center',
              borderRadius: 999,
              backgroundColor: colors.outlineVariant as string,
            }}
          />
          {children}
        </Surface>
        {/* Outside the sliding surface, so it holds still while the sheet
            moves and stays above it. */}
        <SheetToast message={toast} />
      </Modal>
    </Portal>
  );
}

/** A block inside the sheet. Callers pass their own spacing through `style`. */
function SheetContent({
  style,
  children,
}: {
  style?: StyleProp<ViewStyle>;
  children: React.ReactNode;
}): React.ReactElement {
  return <View style={style}>{children}</View>;
}

/**
 * A toast rendered over the sheet's surface.
 *
 * The app's Toast sits in the screen tree underneath the sheet, so a notice
 * raised while the sheet was open was invisible and its timer expired unseen.
 * Rendered as a sibling of the sheet surface, pinned to the bottom, so it sits
 * over the sheet rather than scrolling with its content.
 */
function SheetToast({
  message,
}: {
  message: string;
}): React.ReactElement | null {
  const insets = useSafeInsets();
  const { colors } = useTheme();
  if (message === '') return null;
  return (
    <Surface
      elevation={3}
      pointerEvents="none"
      style={{
        position: 'absolute',
        left: 16,
        right: 16,
        bottom: insets.bottom + 16,
        borderRadius: 12,
        paddingHorizontal: 16,
        paddingVertical: 12,
        backgroundColor: colors.inverseSurface as string,
      }}
      accessibilityLiveRegion="polite"
    >
      <Text
        style={{ color: colors.inverseOnSurface as string }}
        numberOfLines={2}
      >
        {message}
      </Text>
    </Surface>
  );
}

function SheetHeader({
  children,
}: {
  children: React.ReactNode;
}): React.ReactElement {
  return <View style={{ marginBottom: 12, gap: 4 }}>{children}</View>;
}

function SheetTitle({
  children,
}: {
  children: React.ReactNode;
}): React.ReactElement {
  return (
    <Text role="heading" variant="h3">
      {children}
    </Text>
  );
}

export { Sheet, SheetContent, SheetHeader, SheetTitle, SheetToast };
