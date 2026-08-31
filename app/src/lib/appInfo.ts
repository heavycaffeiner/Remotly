// Build identity for the Settings screen. Read natively from the package
// manager, so the reported version cannot drift from the installed APK.

import NativeAppInfo from '../specs/NativeRemotlyAppInfo';

export interface AppInfo {
  versionName: string;
  versionCode: string;
  protocolVersion: string;
  androidSdk: number;
}

export const UNKNOWN_APP_INFO: AppInfo = {
  versionName: '',
  versionCode: '',
  protocolVersion: '',
  androidSdk: 0,
};

export function getAppInfo(): Promise<AppInfo> {
  return NativeAppInfo.get().then(
    d => ({
      versionName: typeof d.versionName === 'string' ? d.versionName : '',
      versionCode: typeof d.versionCode === 'string' ? d.versionCode : '',
      protocolVersion:
        typeof d.protocolVersion === 'string' ? d.protocolVersion : '',
      androidSdk: typeof d.androidSdk === 'number' ? d.androidSdk : 0,
    }),
    // Version display is not worth failing a screen over.
    () => UNKNOWN_APP_INFO,
  );
}
