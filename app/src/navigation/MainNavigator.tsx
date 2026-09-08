// The tabbed shell: Hosts and Settings.
//
// Compact widths get a bottom bar; expanded widths get a rail beside the
// content. Selection carries a filled icon and an accessibilityState, so it
// never depends on color alone.
//
// Every destination stays mounted. Each screen owns its own loading state, and
// remounting would refetch the host list on every tab change.

import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  AccessibilityInfo,
  Animated,
  useAnimatedValue,
  View,
  type StyleProp,
  type ViewStyle,
} from 'react-native';
import { Surface, TouchableRipple, useTheme } from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { HostsScreen } from '../features/hosts/HostsScreen';
import { SettingsScreen } from '../features/settings/SettingsScreen';
import { Icon } from '../components/ui/icon';
import type { IconName } from '../lib/icons';
import { Text } from '../components/ui/text';
import { useWidthClass } from '../theme/useColorScheme';
import { MAIN_TABS, type MainTab } from './types';

interface TabDef {
  key: MainTab;
  title: string;
  icon: IconName;
}

const TABS: readonly TabDef[] = [
  { key: 'Hosts', title: 'Hosts', icon: 'server' },
  { key: 'Settings', title: 'Settings', icon: 'cog' },
];

export function MainNavigator({
  route,
}: {
  route?: { params?: { tab?: MainTab } };
}): React.ReactElement {
  const initial = route?.params?.tab;
  const [active, setActive] = useState<MainTab>(
    initial && MAIN_TABS.includes(initial) ? initial : 'Hosts',
  );
  const expanded = useWidthClass() === 'expanded';
  const { colors } = useTheme();

  // Kept mounted and toggled with display, so a screen's state and in-flight
  // requests survive a tab switch.
  const scenes = (
    <>
      <Scene active={active === 'Hosts'}>
        <HostsScreen />
      </Scene>
      <Scene active={active === 'Settings'}>
        <SettingsScreen />
      </Scene>
    </>
  );

  if (expanded) {
    return (
      <View
        style={{
          flex: 1,
          flexDirection: 'row',
          backgroundColor: colors.background,
        }}
      >
        <NavigationRail active={active} onSelect={setActive} />
        <View style={{ flex: 1 }}>{scenes}</View>
      </View>
    );
  }

  return (
    <View style={{ flex: 1, backgroundColor: colors.background }}>
      <View style={{ flex: 1 }}>{scenes}</View>
      <NavigationBar active={active} onSelect={setActive} />
    </View>
  );
}

function Scene({
  active,
  children,
}: {
  active: boolean;
  children: React.ReactNode;
}): React.ReactElement {
  return (
    <View
      // Hidden scenes are removed from the accessibility tree as well, so a
      // screen reader cannot land on an off-screen destination.
      accessibilityElementsHidden={!active}
      importantForAccessibility={active ? 'auto' : 'no-hide-descendants'}
      pointerEvents={active ? 'auto' : 'none'}
      style={{ flex: 1, display: active ? 'flex' : 'none' }}
    >
      {children}
    </View>
  );
}

interface NavProps {
  active: MainTab;
  onSelect: (tab: MainTab) => void;
}

function NavigationBar({ active, onSelect }: NavProps): React.ReactElement {
  const insets = useSafeAreaInsets();
  const { colors } = useTheme();
  return (
    <Surface
      elevation={2}
      accessibilityRole="tablist"
      style={{
        height: 80 + insets.bottom,
        flexDirection: 'row',
        paddingBottom: insets.bottom,
        backgroundColor: colors.surfaceContainer,
      }}
    >
      {TABS.map(tab => (
        <NavItem
          key={tab.key}
          tab={tab}
          selected={active === tab.key}
          onSelect={onSelect}
          style={{ flex: 1 }}
        />
      ))}
    </Surface>
  );
}

function NavigationRail({ active, onSelect }: NavProps): React.ReactElement {
  const insets = useSafeAreaInsets();
  const { colors } = useTheme();
  return (
    <Surface
      elevation={2}
      accessibilityRole="tablist"
      style={{
        width: 80,
        gap: 12,
        paddingHorizontal: 8,
        paddingTop: insets.top + 16,
        paddingBottom: insets.bottom,
        backgroundColor: colors.surfaceContainer,
      }}
    >
      {TABS.map(tab => (
        <NavItem
          key={tab.key}
          tab={tab}
          selected={active === tab.key}
          onSelect={onSelect}
          style={{ width: 64 }}
        />
      ))}
    </Surface>
  );
}

interface NavItemProps {
  tab: TabDef;
  selected: boolean;
  onSelect: (tab: MainTab) => void;
  style?: StyleProp<ViewStyle>;
}

function NavItem({
  tab,
  selected,
  onSelect,
  style,
}: NavItemProps): React.ReactElement {
  const press = useCallback(() => onSelect(tab.key), [onSelect, tab.key]);
  const { colors } = useTheme();

  // The selection pill grows in rather than appearing.
  const grow = useAnimatedValue(selected ? 1 : 0);
  const reduceMotion = useReducedMotion();
  useEffect(() => {
    if (reduceMotion) {
      grow.setValue(selected ? 1 : 0);
      return undefined;
    }
    const animation = Animated.timing(grow, {
      toValue: selected ? 1 : 0,
      duration: 180,
      useNativeDriver: true,
    });
    animation.start();
    return () => animation.stop();
  }, [selected, grow, reduceMotion]);

  return (
    <TouchableRipple
      onPress={press}
      borderless
      accessibilityRole="tab"
      accessibilityLabel={tab.title}
      accessibilityState={{ selected }}
      style={[
        { height: 80, alignItems: 'center', justifyContent: 'center' },
        style,
      ]}
    >
      <View style={{ alignItems: 'center', paddingVertical: 8 }}>
        <View
          style={{
            height: 32,
            width: 64,
            alignItems: 'center',
            justifyContent: 'center',
            overflow: 'hidden',
            borderRadius: 999,
          }}
        >
          <Animated.View
            accessibilityElementsHidden
            importantForAccessibility="no-hide-descendants"
            style={{
              position: 'absolute',
              top: 0,
              left: 0,
              right: 0,
              bottom: 0,
              borderRadius: 999,
              backgroundColor: colors.secondaryContainer,
              opacity: grow,
              transform: [{ scaleX: grow }],
            }}
          />
          <Icon
            name={tab.icon}
            size={24}
            color={
              selected
                ? (colors.onSecondaryContainer as string)
                : (colors.onSurfaceVariant as string)
            }
          />
        </View>
        <Text
          variant="caption"
          style={{
            marginTop: 4,
            fontWeight: selected ? '600' : '500',
            color: selected
              ? (colors.onSurface as string)
              : (colors.onSurfaceVariant as string),
          }}
        >
          {tab.title}
        </Text>
      </View>
    </TouchableRipple>
  );
}

// Honors the OS "remove animations" setting. An animation the user has asked
// not to see is a accessibility failure, not a polish detail.
function useReducedMotion(): boolean {
  const [reduced, setReduced] = useState(false);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    void AccessibilityInfo.isReduceMotionEnabled().then(value => {
      if (mounted.current) setReduced(value);
    });
    const sub = AccessibilityInfo.addEventListener(
      'reduceMotionChanged',
      value => setReduced(value),
    );
    return () => {
      mounted.current = false;
      sub.remove();
    };
  }, []);
  return reduced;
}
