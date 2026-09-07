import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

// Posts a desktop notification the running program asked for with OSC 9 or
// OSC 777. The text comes from the remote, so the native side bounds it and
// posts nothing when the user has not granted the permission.

export interface Spec extends TurboModule {
  /**
   * Shows a notification. An empty title falls back to the app name, which is
   * the OSC 9 case: it carries a body only.
   *
   * Resolves false when the notification was not shown, which is an ordinary
   * outcome rather than an error: the permission may be denied.
   */
  notify(title: string, body: string): Promise<boolean>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RemotlyNotify');
