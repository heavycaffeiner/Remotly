import React from 'react';
import { ActivityIndicator, View } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { PortalHost } from '@rn-primitives/portal';

import { RootNavigator } from './navigation/RootNavigator';
import { SettingsProvider } from './theme/SettingsProvider';
import '../global.css';

// Settings load before the navigator renders so the first frame already has
// the right theme. Rendering dark and then switching to light is a visible
// flash, so a neutral placeholder covers the load instead.
export default function App(): React.ReactElement {
  return (
    <SafeAreaProvider>
      <SettingsProvider fallback={<StartupPlaceholder />}>
        <RootNavigator />
        <PortalHost />
      </SettingsProvider>
    </SafeAreaProvider>
  );
}

// Deliberately themeless: a mid-tone neutral reads as intentional under either
// a light or a dark final theme.
function StartupPlaceholder(): React.ReactElement {
  return (
    <View className="flex-1 items-center justify-center bg-surface">
      <ActivityIndicator accessibilityLabel="Starting" />
    </View>
  );
}
