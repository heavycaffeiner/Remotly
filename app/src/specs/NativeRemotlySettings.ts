import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

// Spec for the global app settings bridge. Error codes: -1 unavailable
// context, -2 store failure, -3 invalid parameter.
//
// The stored schema is version 2. A version 1 file (notifyEnabled only) is
// migrated forward natively, so an upgrade preserves the user's choice.

export interface SettingsShape {
  /** In-app master switch for terminal event notifications. */
  notifyEnabled: boolean;
  /** 'system' | 'light' | 'dark' */
  themeMode: string;
  /** Use the Android dynamic color scheme when the platform supports it. */
  dynamicColor: boolean;
  /** Terminal font size in sp, 8 to 32. */
  terminalFontSize: number;
  /** Open the keyboard when a terminal is opened by an explicit user action. */
  openKeyboardOnTerminal: boolean;
  /** Show the extra terminal key row. */
  showExtraKeyRow: boolean;
  /** 'block' | 'bar' | 'underline' */
  cursorStyle: string;
}

export interface Spec extends TurboModule {
  /** Reads the global app settings. */
  get(): Promise<SettingsShape>;

  /** Replaces the global app settings. Rejects on an out-of-range value. */
  set(settings: SettingsShape): Promise<void>;

  /**
   * Restores the default preferences and resolves with them.
   *
   * Preferences only. Paired hosts, SSH credentials, accepted host keys, and
   * workspace state are not touched.
   */
  reset(): Promise<SettingsShape>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RemotlySettings');
