import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

// Build identity for the Settings screen. Read natively from the package
// manager so the reported version cannot drift from the installed APK.

export interface AppInfoResult {
  versionName: string;
  /** A string because a long does not survive the bridge as a number. */
  versionCode: string;
  /** The wire protocol version, which moves independently of the app version. */
  protocolVersion: string;
  androidSdk: number;
}

export interface Spec extends TurboModule {
  get(): Promise<AppInfoResult>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RemotlyAppInfo');
