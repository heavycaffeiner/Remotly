'use strict';

// Global Jest setup: stub every native TurboModule spec so the app tree
// (RootNavigator and its screens) can render without the native bridge. A
// per-test jest.mock in an individual test file takes precedence over these
// stubs, so the lib unit tests keep their specific behavior.
//
// Each default export is an object of jest.fn stubs. Emitter methods (onXxx)
// return an EventSubscription-shaped object with remove(); list methods resolve
// an empty list; the one-shot take methods resolve their "empty" shape.

const emitter = () => jest.fn(() => ({ remove: jest.fn() }));
const resolved = (v) => jest.fn().mockResolvedValue(v);

jest.mock('./src/specs/NativeRemotlyHosts', () => ({
  __esModule: true,
  default: {
    add: resolved({ id: 'mock-host', duplicate: false }),
    list: resolved({ hosts: [] }),
    remove: resolved(undefined),
    touch: resolved(undefined),
  },
}));

jest.mock('./src/specs/NativeRemotlyPairing', () => ({
  __esModule: true,
  default: { takePending: resolved({ uri: '' }) },
}));

jest.mock('./src/specs/NativeRemotlySshHost', () => ({
  __esModule: true,
  default: {
    list: resolved({ hosts: [] }),
    add: resolved({ host: {} }),
    update: resolved({ host: {} }),
    testConnection: resolved({
      ok: true,
      code: '',
      stage: '',
      message: '',
      hostKeyAlgorithm: 'ssh-ed25519',
      hostKeyFingerprint: 'SHA256:test',
      hostKeyKnown: false,
      hostKeyChanged: false,
    }),
    setCredential: resolved(undefined),
    rename: resolved(undefined),
    remove: resolved(undefined),
  },
}));

jest.mock('./src/specs/NativeRemotlySsh', () => ({
  __esModule: true,
  default: {
    connect: resolved(undefined),
    write: resolved(undefined),
    resize: resolved(undefined),
    hostKey: resolved(undefined),
    close: resolved(undefined),
    storeOpen: resolved(undefined),
    takeOpen: resolved({ hostId: '' }),
    onState: emitter(),
    onData: emitter(),
  },
}));

jest.mock('./src/specs/NativeRemotlyTerminalStore', () => ({
  __esModule: true,
  default: {
    feed: resolved(true),
    has: resolved(true),
  },
}));

jest.mock('./src/specs/NativeRemotlySftp', () => ({
  __esModule: true,
  default: {
    connect: resolved(undefined),
    startUpload: resolved(''),
    writeChunk: resolved(0),
    completeUpload: resolved(undefined),
    startDownload: resolved(''),
    cancelTransfer: resolved(undefined),
    onTransfer: () => ({ remove: () => {} }),
    status: resolved({ connected: false }),
    hostKey: resolved(undefined),
    list: resolved({ entries: [] }),
    stat: resolved(null),
    mkdir: resolved(undefined),
    rename: resolved(undefined),
    remove: resolved(undefined),
    close: resolved(undefined),
  },
}));

jest.mock('./src/specs/NativeRemotlyFiles', () => ({
  __esModule: true,
  default: {
    storeOpen: resolved(undefined),
    takeOpen: resolved({ open: '' }),
  },
}));

jest.mock('./src/specs/NativeRemotlyFileIO', () => ({
  __esModule: true,
  default: {
    pick: resolved(undefined),
    readChunk: resolved({ data: '' }),
    writeChunk: resolved({ wrote: 0 }),
    release: resolved(undefined),
    discard: resolved(undefined),
    setTransfersActive: resolved(undefined),
  },
}));

jest.mock('./src/specs/NativeRemotlyTransport', () => ({
  __esModule: true,
  default: {
    connect: resolved({}),
    close: resolved(undefined),
    status: resolved({ connected: false, state: 'disconnected' }),
    control: resolved({}),
    writeTerm: resolved(undefined),
    openFile: resolved(undefined),
    writeFile: resolved(undefined),
    onConnected: emitter(),
    onDisconnected: emitter(),
    onSessionUpdate: emitter(),
    onChannelClose: emitter(),
    onReplayComplete: emitter(),
    onTermData: emitter(),
    onFileData: emitter(),
    onSessionEvent: emitter(),
  },
}));

jest.mock('./src/specs/NativeRemotlyWorkspace', () => ({
  __esModule: true,
  default: {
    load: resolved({ json: '' }),
    save: resolved(undefined),
    clear: resolved(undefined),
    open: resolved(undefined),
    takeOpen: resolved({ hostId: '' }),
  },
}));

jest.mock('./src/specs/NativeRemotlySettings', () => ({
  __esModule: true,
  default: {
    get: resolved({
      notifyEnabled: false,
      themeMode: 'system',
      dynamicColor: true,
      terminalFontSize: 14,
      openKeyboardOnTerminal: true,
      showExtraKeyRow: true,
      cursorStyle: 'block',
    }),
    set: resolved(undefined),
    reset: resolved({
      notifyEnabled: false,
      themeMode: 'system',
      dynamicColor: true,
      terminalFontSize: 14,
      openKeyboardOnTerminal: true,
      showExtraKeyRow: true,
      cursorStyle: 'block',
    }),
  },
}));

jest.mock('./src/specs/NativeRemotlyAppInfo', () => ({
  __esModule: true,
  default: {
    get: resolved({
      versionName: '1.0',
      versionCode: '1',
      protocolVersion: '1.0.0',
      androidSdk: 34,
    }),
  },
}));

jest.mock('./src/specs/NativeRemotlyNotify', () => ({
  __esModule: true,
  default: {
    post: resolved(undefined),
    permission: resolved({ granted: true }),
  },
}));

jest.mock('./src/specs/NativeRemotlyCamera', () => ({
  __esModule: true,
  default: {
    getCameraPermissionStatus: resolved({ granted: true, canAskAgain: false }),
    requestCameraPermission: resolved({ granted: true, canAskAgain: false }),
    openAppSettings: resolved(undefined),
  },
}));
