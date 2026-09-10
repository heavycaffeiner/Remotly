// A navigation drawer anchored to the leading edge.
//
// Paper 6 ships Drawer.Section and Drawer.Item as content, not as a container,
// so the container is Paper's Portal + Modal (which own the scrim, the outside
// tap, and the Android back button) with a Surface slid in from the left.
//
// No edge-swipe gesture, on purpose: a horizontal swipe over an attached
// terminal moves a herdr tab, and a drawer that also claimed the left edge
// would fight it on every attempt. It opens from the bar, and where there is
// room it does not open at all because it is already there: `permanent` renders
// the same panel as a plain column the caller lays out beside the content.

import * as React from 'react';
import {
  Animated,
  Easing,
  Pressable,
  useWindowDimensions,
  View,
  type StyleProp,
  type ViewStyle,
} from 'react-native';
import { Modal, Portal, Surface, useTheme } from 'react-native-paper';
import { SafeAreaInsetsContext } from 'react-native-safe-area-context';

const ZERO_INSETS = { top: 0, bottom: 0, left: 0, right: 0 };
function useSafeInsets() {
  const ctx = React.useContext(SafeAreaInsetsContext);
  return ctx ?? ZERO_INSETS;
}

/** Width the panel wants, capped so it never swallows a narrow screen. */
const WIDTH = 300;
const MAX_FRACTION = 0.86;

const DURATION_MS = 200;

/**
 * Whether the window has room to keep the drawer open beside the content.
 *
 * A landscape phone qualifies: the panel costs less than half the width and
 * removes a tap from every workspace and tab move.
 */
export function useDrawerPermanent(): boolean {
  const { width } = useWindowDimensions();
  return width >= 720;
}

interface DrawerProps {
  open: boolean;
  /** Called for a back-button press or a scrim tap. Unused when permanent. */
  onClose: () => void;
  /** Renders as a column in the caller's layout instead of over the content. */
  permanent?: boolean;
  style?: StyleProp<ViewStyle>;
  children: React.ReactNode;
}

function Drawer({
  open,
  onClose,
  permanent = false,
  style,
  children,
}: DrawerProps): React.ReactElement | null {
  const slide = React.useRef(new Animated.Value(0)).current;
  const insets = useSafeInsets();
  const { colors } = useTheme();
  const { width: windowWidth } = useWindowDimensions();
  const width = Math.min(WIDTH, Math.round(windowWidth * MAX_FRACTION));

  React.useEffect(() => {
    Animated.timing(slide, {
      toValue: open ? 1 : 0,
      duration: DURATION_MS,
      easing: open ? Easing.out(Easing.cubic) : Easing.in(Easing.cubic),
      useNativeDriver: true,
    }).start();
  }, [open, slide]);

  const panel = (
    <View
      style={[
        {
          flex: 1,
          paddingTop: insets.top,
          paddingBottom: insets.bottom,
          paddingLeft: insets.left,
        },
        style,
      ]}
    >
      {children}
    </View>
  );

  if (permanent) {
    return (
      <Surface
        elevation={0}
        style={{
          width,
          borderRightWidth: 1,
          borderRightColor: colors.outlineVariant as string,
          backgroundColor: colors.surfaceContainerLow as string,
        }}
      >
        {panel}
      </Surface>
    );
  }

  return (
    <Portal>
      <Modal
        visible={open}
        onDismiss={onClose}
        // The panel owns its own padding, and the modal's default centering
        // would leave it floating in the middle of the screen. Filling the
        // modal means its own scrim never receives a touch, so the space
        // beside the panel is a dismiss target of its own, which is also the
        // one a screen reader can find.
        contentContainerStyle={{ flex: 1, flexDirection: 'row' }}
        style={{ justifyContent: 'flex-start' }}
      >
        <Animated.View
          style={{
            width,
            height: '100%',
            transform: [
              {
                translateX: slide.interpolate({
                  inputRange: [0, 1],
                  outputRange: [-width, 0],
                }),
              },
            ],
          }}
        >
          <Surface
            elevation={1}
            style={{
              flex: 1,
              backgroundColor: colors.surfaceContainerLow as string,
            }}
          >
            {panel}
          </Surface>
        </Animated.View>
        <Pressable
          style={{ flex: 1 }}
          role="button"
          accessibilityLabel="Close the sidebar"
          onPress={onClose}
        />
      </Modal>
    </Portal>
  );
}

export { Drawer };
