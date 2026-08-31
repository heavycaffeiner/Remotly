// App-side client for OS notifications (native: com.remotly.app.notify).
//
// The app decides when to notify (dedupe, the in-app toggle, text
// sanitization); the native side only checks OS preconditions (runtime
// permission on 33+, OS-level disable) and posts. A denied permission or an
// OS-disabled notification is not an error: postOSNotification resolves with
// posted: false and the in-app banner still shows.
import NativeNotify from '../specs/NativeRemotlyNotify';
import { makeRemotlyError } from './errors';

// Adapts a TurboModule rejection to a branded RemotlyError (the module rejects
// with the numeric bridge code as a string).
function fromRejection(e: unknown) {
  const code = Number((e as { code?: string })?.code ?? 0);
  const msg = (e as Error)?.message ?? 'bridge call failed';
  return makeRemotlyError(
    'unknown',
    code === -3 ? 'notify-invalid-params' : code,
    new Error(msg),
  );
}

export interface PostNotificationArgs {
  hostId: string;
  /** The daemon's 64-hex-character session id; used to derive a stable
   * notification id. */
  sessionId: string;
  /** Display name of the host. Sanitized by the caller. */
  hostName: string;
  /** Short line, at most 100 chars after the native truncation. */
  title: string;
  /** Body line, at most 300 chars after the native truncation. */
  text: string;
}

export interface PostNotificationResult {
  /** True only when the notification was actually submitted to the OS. */
  posted: boolean;
}

export async function postOSNotification(
  args: PostNotificationArgs,
): Promise<PostNotificationResult> {
  return NativeNotify.post(
    args.hostId,
    args.sessionId,
    args.hostName,
    args.title,
    args.text,
  ).then(
    d => ({ posted: d.posted === true }),
    e => {
      throw fromRejection(e);
    },
  );
}

export interface PermissionState {
  /** POST_NOTIFICATIONS runtime permission granted (always true below 33). */
  granted: boolean;
  /** Notifications enabled at the OS level (not per-app muted). */
  osEnabled: boolean;
  /** True when a system request was just launched; the result is async. */
  requested: boolean;
  /** Last known permission result, or null when none was recorded. */
  lastResult: boolean | null;
}

export async function queryNotificationPermission(
  request: boolean,
): Promise<PermissionState> {
  return NativeNotify.permission(request).then(
    d => ({
      granted: d.granted === true,
      osEnabled: d.osEnabled === true,
      requested: d.requested === true,
      lastResult: typeof d.lastResult === 'boolean' ? d.lastResult : null,
    }),
    e => {
      throw fromRejection(e);
    },
  );
}
