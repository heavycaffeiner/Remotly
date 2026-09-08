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
  PanResponder,
  ScrollView,
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

/** How far down a drag has to end for the sheet to close rather than settle. */
const DISMISS_DISTANCE = 96;

/** A flick past this speed closes it regardless of distance. */
const DISMISS_VELOCITY = 0.7;

function Sheet({
  open,
  onClose,
  toast = '',
  children,
}: SheetProps): React.ReactElement {
  const slide = React.useRef(new Animated.Value(0)).current;
  const drag = React.useRef(new Animated.Value(0)).current;
  const insets = useSafeInsets();
  const { colors } = useTheme();

  // The sheet is measured rather than assumed. A fixed travel distance starts
  // a short sheet off-screen and only catches up at the end, which reads as a
  // jump, and clips one taller than the guess. Seeded with the window height
  // so the very first frame is off-screen rather than flashing in place.
  const { height: windowHeight } = useWindowDimensions();
  const [sheetHeight, setSheetHeight] = React.useState(windowHeight);

  React.useEffect(() => {
    // Opening starts from the closed position, not from wherever the last
    // drag left it.
    if (open) drag.setValue(0);
    Animated.timing(slide, {
      toValue: open ? 1 : 0,
      duration: DURATION_MS,
      easing: open ? Easing.out(Easing.cubic) : Easing.in(Easing.cubic),
      useNativeDriver: true,
    }).start();
  }, [open, slide, drag]);

  // Dragging the grip pulls the sheet down and lets go of it. Only downward:
  // pulling up would leave a gap under a sheet that is already at the bottom
  // edge. The gesture lives on the grip rather than the whole surface so a
  // list inside keeps its own scrolling.
  const pan = React.useMemo(
    () =>
      PanResponder.create({
        onMoveShouldSetPanResponder: (_e, g) =>
          g.dy > 2 && Math.abs(g.dy) > Math.abs(g.dx),
        onPanResponderMove: (_e, g) => {
          drag.setValue(Math.max(0, g.dy));
        },
        onPanResponderRelease: (_e, g) => {
          if (g.dy > DISMISS_DISTANCE || g.vy > DISMISS_VELOCITY) {
            // Left where it is: the close animation carries it the rest of
            // the way down, so releasing does not snap back up first.
            onClose();
            return;
          }
          Animated.spring(drag, {
            toValue: 0,
            useNativeDriver: true,
            bounciness: 0,
          }).start();
        },
        onPanResponderTerminate: () => {
          Animated.spring(drag, {
            toValue: 0,
            useNativeDriver: true,
            bounciness: 0,
          }).start();
        },
      }),
    [drag, onClose],
  );

  const translateY = Animated.add(
    slide.interpolate({
      inputRange: [0, 1],
      outputRange: [sheetHeight, 0],
    }),
    drag,
  );

  return (
    <Portal>
      <Modal
        visible={open}
        onDismiss={onClose}
        overlayAccessibilityLabel="Dismiss"
        // Paper centers its content and insets the wrapper. The sheet has to
        // reach the bottom edge instead and pads for the gesture bar itself.
        style={{ justifyContent: 'flex-end', marginBottom: 0 }}
        // Paper's own container centers its child too, so bottom-aligning the
        // wrapper is not enough: without this the sheet floats in the middle
        // of the screen with a gap beneath it.
        contentContainerStyle={{
          justifyContent: 'flex-end',
          transform: [{ translateY }],
        }}
      >
        <Surface
          elevation={3}
          onLayout={e => {
            const h = e.nativeEvent.layout.height;
            if (h > 0) setSheetHeight(h);
          }}
          style={{
            maxHeight: '85%',
            borderTopLeftRadius: 28,
            borderTopRightRadius: 28,
          }}
        >
          {/* The grip: a wide target rather than the bar alone, since the bar
              is 4dp tall and nobody can land on that. */}
          <View
            {...pan.panHandlers}
            accessibilityElementsHidden
            importantForAccessibility="no-hide-descendants"
            style={{ paddingTop: 12, paddingBottom: 8 }}
          >
            <View
              style={{
                height: 4,
                width: 32,
                alignSelf: 'center',
                borderRadius: 999,
                backgroundColor: colors.outlineVariant as string,
              }}
            />
          </View>
          {/* Scrolls rather than clipping: a sheet taller than its 85% cap
              would otherwise lose its last rows with no way to reach them. */}
          <ScrollView
            keyboardShouldPersistTaps="always"
            contentContainerStyle={{
              paddingHorizontal: 16,
              // The gesture bar sits under the sheet's own bottom edge, so the
              // inset is added to the padding rather than replacing it. The
              // last row is a target, and one flush against the bar is one the
              // system swipe takes instead.
              paddingBottom: insets.bottom + 28,
            }}
          >
            {children}
          </ScrollView>
        </Surface>
        {/* Inside the sliding container, so it travels with the sheet and
            stays above its surface. */}
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
