import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

// Spec for the OS-notification bridge (remotly.notify.*).

export interface PostResult {
  /** True only when the notification was actually submitted. */
  posted: boolean;
}

export interface PermissionResult {
  /** POST_NOTIFICATIONS granted (always true below Android 13). */
  granted: boolean;
  /** User has not disabled notifications for the app at the OS level. */
  osEnabled: boolean;
  /** True when this call raised the system permission dialog. */
  requested: boolean;
  /** Outcome of the most recent dialog in this process, null before any. */
  lastResult: boolean | null;
}

export interface Spec extends TurboModule {
  /**
   * Posts one terminal event notification. The app layer has already deduped
   * the event, honored the in-app toggle, and sanitized the text. A denied
   * permission makes this a silent success (posted false).
   */
  post(
    hostId: string,
    sessionId: string,
    hostName: string,
    title: string,
    text: string,
  ): Promise<PostResult>;

  /**
   * Reports the notification permission state and, when request is set and
   * the permission is missing, raises the system dialog. The dialog result is
   * asynchronous: this call reports the state as known now.
   */
  permission(request: boolean): Promise<PermissionResult>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RemotlyNotify');
