import React, { useMemo } from 'react';
import { ActivityIndicator, View } from 'react-native';
import { PaperProvider } from 'react-native-paper';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { RootNavigator } from './navigation/RootNavigator';
import { dynamicColorsFor } from './lib/dynamicColors';
import { SettingsProvider } from './theme/SettingsProvider';
import { paperTheme } from './theme/paperTheme';
import {
  DynamicColorsProvider,
  useDynamicColors,
} from './theme/useDynamicColors';
import { useAppliedColorScheme } from './theme/useColorScheme';

// Settings and the Material You scheme load before the tree renders, so the
// first painted frame already has the final theme. A neutral placeholder
// covers the load instead of a flash.
export default function App(): React.ReactElement {
  return (
    <SafeAreaProvider>
      <SettingsProvider fallback={<StartupPlaceholder />}>
        <DynamicColorsProvider>
          <Gate>
            <RootNavigator />
          </Gate>
        </DynamicColorsProvider>
      </SettingsProvider>
    </SafeAreaProvider>
  );
}

// The PaperProvider sits behind the startup gate: its theme must be the
// final one (default or wallpaper-derived) before anything renders with it.
function Gate({ children }: { children: React.ReactNode }): React.ReactElement {
  const scheme = useAppliedColorScheme();
  const { ready, colors } = useDynamicColors();
  const theme = useMemo(
    () =>
      paperTheme(
        scheme,
        dynamicColorsFor(colors ?? { available: false }, scheme),
      ),
    [scheme, colors],
  );
  if (!ready) return <StartupPlaceholder />;
  return <PaperProvider theme={theme}>{children}</PaperProvider>;
}

// Deliberately themeless: a mid-tone neutral reads as intentional under
// either a light or a dark final theme.
function StartupPlaceholder(): React.ReactElement {
  return (
    <View
      style={{
        flex: 1,
        alignItems: 'center',
        justifyContent: 'center',
        backgroundColor: '#202124',
      }}
    >
      <ActivityIndicator accessibilityLabel="Starting" />
    </View>
  );
}
