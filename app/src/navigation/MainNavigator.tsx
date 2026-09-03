// The tabbed shell: Hosts, Sessions, and Settings.
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
  Pressable,
  useAnimatedValue,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { HostsScreen } from '../features/hosts/HostsScreen';
import { SessionsScreen } from '../features/sessions/SessionsScreen';
import { SettingsScreen } from '../features/settings/SettingsScreen';
import { Icon, type IconName } from '../components/ui/icon';
import { Text } from '../components/ui/text';
import { cn } from '../lib/utils';
import { useWidthClass } from '../theme/useColorScheme';
import { MAIN_TABS, type MainTab } from './types';

interface TabDef {
  key: MainTab;
  title: string;
  icon: IconName;
}

const TABS: readonly TabDef[] = [
  { key: 'Hosts', title: 'Hosts', icon: 'server' },
  { key: 'Sessions', title: 'Sessions', icon: 'layout-dashboard' },
  { key: 'Settings', title: 'Settings', icon: 'settings' },
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

  // Kept mounted and toggled with display, so a screen's state and in-flight
  // requests survive a tab switch.
  const scenes = (
    <>
      <Scene active={active === 'Hosts'}>
        <HostsScreen />
      </Scene>
      <Scene active={active === 'Sessions'}>
        <SessionsScreen />
      </Scene>
      <Scene active={active === 'Settings'}>
        <SettingsScreen />
      </Scene>
    </>
  );

  if (expanded) {
    return (
      <View className="flex-1 flex-row bg-background">
        <NavigationRail active={active} onSelect={setActive} />
        <View className="flex-1">{scenes}</View>
      </View>
    );
  }

  return (
    <View className="flex-1 bg-background">
      <View className="flex-1">{scenes}</View>
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
      style={{ display: active ? 'flex' : 'none' }}
      className="flex-1"
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
  return (
    <View
      accessibilityRole="tablist"
      style={{ paddingBottom: insets.bottom }}
      className="h-20 flex-row bg-surface-container"
    >
      {TABS.map(tab => (
        <NavItem
          key={tab.key}
          tab={tab}
          selected={active === tab.key}
          onSelect={onSelect}
          className="flex-1"
        />
      ))}
    </View>
  );
}

function NavigationRail({ active, onSelect }: NavProps): React.ReactElement {
  const insets = useSafeAreaInsets();
  return (
    <View
      accessibilityRole="tablist"
      style={{ paddingTop: insets.top + 16, paddingBottom: insets.bottom }}
      className="w-20 gap-3 bg-surface-container px-2"
    >
      {TABS.map(tab => (
        <NavItem
          key={tab.key}
          tab={tab}
          selected={active === tab.key}
          onSelect={onSelect}
          className="w-16"
        />
      ))}
    </View>
  );
}

interface NavItemProps {
  tab: TabDef;
  selected: boolean;
  onSelect: (tab: MainTab) => void;
  className?: string;
}

function NavItem({
  tab,
  selected,
  onSelect,
  className,
}: NavItemProps): React.ReactElement {
  const press = useCallback(() => onSelect(tab.key), [onSelect, tab.key]);

  // The selection pill grows in rather than appearing. useNativeDriver is off
  // because scaleX drives layout-adjacent geometry here, and the pill is a
  // single small view per tab.
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
    <Pressable
      onPress={press}
      accessibilityRole="tab"
      accessibilityLabel={tab.title}
      accessibilityState={{ selected }}
      android_ripple={{
        color: 'rgba(0, 0, 0, 0.08)',
        borderless: true,
        radius: 28,
      }}
      className={cn('h-20 items-center justify-center py-2', className)}
    >
      <View className="h-8 w-16 items-center justify-center overflow-hidden rounded-full">
        <Animated.View
          accessibilityElementsHidden
          importantForAccessibility="no-hide-descendants"
          style={{ opacity: grow, transform: [{ scaleX: grow }] }}
          className="absolute inset-0 rounded-full bg-secondary"
        />
        <Icon
          name={tab.icon}
          size={24}
          className={
            selected ? 'text-secondary-foreground' : 'text-on-surface-variant'
          }
        />
      </View>
      <Text
        className={cn(
          'mt-1 text-xs tracking-tight',
          selected
            ? 'font-semibold text-foreground'
            : 'text-on-surface-variant font-medium',
        )}
      >
        {tab.title}
      </Text>
    </Pressable>
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
