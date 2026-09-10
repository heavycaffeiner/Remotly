import React, { useMemo } from 'react';
import { Platform, StatusBar } from 'react-native';
import {
  DarkTheme,
  DefaultTheme,
  NavigationContainer,
  type Theme,
} from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { adaptNavigationTheme, useTheme } from 'react-native-paper';

import { MainNavigator } from './MainNavigator';
import { SshTerminalScreen } from '../features/ssh-terminal/SshTerminalScreen';
import { SshHostEditorScreen } from '../features/hosts/SshHostEditorScreen';
import { FilesScreen } from '../features/files/FilesScreen';
import { TransferIndicator } from '../features/files/TransferIndicator';
import { HerdrWorkspaceScreen } from '../features/herdr/HerdrWorkspaceScreen';
import { TransferSheet } from '../features/files/TransferSheet';
import type { RootStackParamList } from './types';

const Stack = createNativeStackNavigator<RootStackParamList>();

// The root stack over the tabbed shell.
//
// native-stack (react-native-screens) keeps inactive screens mounted rather
// than recycling them. That is what keeps a live terminal session alive when
// the user navigates away and back, so no per-screen opt-in is needed here.
export function RootNavigator(): React.ReactElement {
  const theme = useTheme();
  const isDark = theme.dark;

  // The navigator's own container colors have to track the theme, or the
  // background flashes the wrong color during a transition.
  const navTheme = useMemo<Theme>(() => {
    const adapted = adaptNavigationTheme({
      reactNavigationLight: DefaultTheme,
      reactNavigationDark: DarkTheme,
      materialLight: theme,
      materialDark: theme,
    });
    return isDark ? adapted.DarkTheme : adapted.LightTheme;
  }, [isDark, theme]);

  return (
    <NavigationContainer theme={navTheme}>
      <StatusBar
        barStyle={
          isDark
            ? 'light-content'
            : Platform.OS === 'ios'
            ? 'dark-content'
            : 'light-content'
        }
      />
      <Stack.Navigator
        initialRouteName="Main"
        screenOptions={{ headerShown: false }}
      >
        <Stack.Screen name="Main" component={MainNavigator} />
        <Stack.Screen name="SshTerminal" component={SshTerminalScreen} />
        <Stack.Screen name="SshHostEditor" component={SshHostEditorScreen} />
        <Stack.Screen name="Files" component={FilesScreen} />
        <Stack.Screen name="HerdrWorkspace" component={HerdrWorkspaceScreen} />
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
