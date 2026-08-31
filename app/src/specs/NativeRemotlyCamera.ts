import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

// Spec for the camera permission bridge. The pairing screen requests CAMERA
// from JS before mounting the QR scanner; denial routes the user to the
// manual-entry path so the flow stays keyboard-accessible end to end.

export interface CameraPermissionResult {
  granted: boolean;
  /**
   * False after a permanent denial, when the system will no longer show the
   * dialog and the only path forward is app settings.
   */
  canAskAgain: boolean;
}

export interface ScanResult {
  /** The decoded value, or "" when the user dismissed the scanner. */
  value: string;
}

export interface Spec extends TurboModule {
  /**
   * Reads the current camera permission without showing a system dialog.
   * `canAskAgain` is false only when this app has previously asked and Android
   * will no longer present the request; the UI can then offer Settings.
   */
  getCameraPermissionStatus(): Promise<CameraPermissionResult>;

  /**
   * Requests the CAMERA runtime permission and resolves with the outcome. If
   * already granted it resolves immediately without a dialog. Concurrent calls
   * share one in-flight request.
   */
  requestCameraPermission(): Promise<CameraPermissionResult>;

  /** Opens this app's system settings page so a permanent denial is fixable. */
  openAppSettings(): Promise<void>;

  /**
   * Launches Google's code scanner and resolves with the decoded value.
   *
   * Resolves with an empty string when the user dismisses it. The scanner owns
   * the camera, its preview, and the camera permission, so nothing here needs
   * a permission of its own.
   *
   * Rejects when Play services cannot supply the scanner, which is the signal
   * to fall back to pasting the link.
   */
  scanCode(): Promise<ScanResult>;

  /**
   * Reads plain text from the system clipboard, or "" when it holds none.
   *
   * Lives here rather than on a clipboard module of its own because this is
   * the only place the app reads one, and react-native's own Clipboard is
   * deprecated and warns on every access.
   */
  readClipboard(): Promise<ScanResult>;

  /**
   * Reads an image from the clipboard as standard base64 PNG.
   *
   * Resolves with an empty value when the clipboard holds no image, which is
   * the ordinary case and not an error. Anything the platform can decode is
   * re-encoded as PNG, so the caller gets one format rather than whatever the
   * source application happened to put there.
   */
  readClipboardImage(): Promise<ClipboardImage>;
}

export interface ClipboardImage {
  /** Standard base64 PNG, or "" when the clipboard holds no image. */
  data: string;
  width: number;
  height: number;
}

export default TurboModuleRegistry.getEnforcing<Spec>('RemotlyCamera');
