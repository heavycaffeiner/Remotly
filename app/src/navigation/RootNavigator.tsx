import React, { useMemo } from 'react';
import { StatusBar } from 'react-native';
import {
  DarkTheme,
  DefaultTheme,
  NavigationContainer,
  type Theme,
} from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';

import { MainNavigator } from './MainNavigator';
import { PairingScreen } from '../features/pairing/PairingScreen';
import { WorkspaceScreen } from '../features/workspace/WorkspaceScreen';
import { SshTerminalScreen } from '../features/ssh-terminal/SshTerminalScreen';
import { SshHostEditorScreen } from '../features/hosts/SshHostEditorScreen';
import { FilesScreen } from '../features/files/FilesScreen';
import { TransferIndicator } from '../features/files/TransferIndicator';
import { TransferSheet } from '../features/files/TransferSheet';
import { themeColors, useAppliedColorScheme } from '../theme/useColorScheme';
import { linking } from './linking';
import type { RootStackParamList } from './types';

const Stack = createNativeStackNavigator<RootStackParamList>();

// The root stack over the tabbed shell.
//
// native-stack (react-native-screens) keeps inactive screens mounted rather
// than recycling them. That is what keeps a live terminal session alive when
// the user navigates away and back, so no per-screen opt-in is needed here.
export function RootNavigator(): React.ReactElement {
  const scheme = useAppliedColorScheme();

  // The navigator's own container colors have to track the theme, or the
  // background flashes the wrong color during a transition.
  const navTheme = useMemo<Theme>(() => {
    const base = scheme === 'dark' ? DarkTheme : DefaultTheme;
    const c = themeColors[scheme];
    return {
      ...base,
      colors: {
        ...base.colors,
        primary: c.primary,
        background: c.background,
        card: c.card,
        text: c.foreground,
        border: c.border,
        notification: c.primary,
      },
    };
  }, [scheme]);

  return (
    <NavigationContainer linking={linking} theme={navTheme}>
      <StatusBar
        barStyle={scheme === 'dark' ? 'light-content' : 'dark-content'}
      />
      <Stack.Navigator
        initialRouteName="Main"
        screenOptions={{ headerShown: false }}
      >
        <Stack.Screen name="Main" component={MainNavigator} />
        <Stack.Screen name="Pairing" component={PairingScreen} />
        <Stack.Screen name="Workspace" component={WorkspaceScreen} />
        <Stack.Screen name="SshTerminal" component={SshTerminalScreen} />
        <Stack.Screen name="SshHostEditor" component={SshHostEditorScreen} />
        <Stack.Screen name="Files" component={FilesScreen} />
      </Stack.Navigator>
      {/* Above the whole stack, not inside a screen. Transfers outlive the
          screen that started them, so an indicator mounted within one
          disappears the moment the user navigates anywhere else, which is
          exactly when they most want to see it. */}
      <TransferIndicator />
      {/* Mounted once, and beside the bar rather than inside it: the sheet
          opens from the files toolbar too, when nothing is transferring and
          the bar is hidden. Two copies drew one sheet stacked on the other. */}
      <TransferSheet />
    </NavigationContainer>
  );
}
