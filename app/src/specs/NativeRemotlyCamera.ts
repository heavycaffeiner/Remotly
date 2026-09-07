import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

// Spec for the clipboard reads the terminal paste actions use, plus the link
// out to this app's system settings page.

export interface ScanResult {
  /** The decoded value, or "" when there was none. */
  value: string;
}

export interface Spec extends TurboModule {
  /** Opens this app's system settings page. */
  openAppSettings(): Promise<void>;

  /**
   * Reads plain text from the system clipboard, or "" when it holds none.
   *
   * Lives here rather than on a clipboard module of its own because this is
   * the only place the app reads one, and react-native's own Clipboard is
   * deprecated and warns on every access.
   */
  readClipboard(): Promise<ScanResult>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RemotlyCamera');
