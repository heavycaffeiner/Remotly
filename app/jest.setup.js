'use strict';

// Global Jest setup: stub every native TurboModule spec so the app tree
// (RootNavigator and its screens) can render without the native bridge. A
// per-test jest.mock in an individual test file takes precedence over these
// stubs, so the lib unit tests keep their specific behavior.
//
// Each default export is an object of jest.fn stubs. Emitter methods (onXxx)
// return an EventSubscription-shaped object with remove(); list methods resolve
// an empty list; the one-shot take methods resolve their "empty" shape.

// Reanimated checks reduced motion through `window.matchMedia` on the path it
// takes without the JSI worklets module. The RN preset defines `window` but
// not that function.
if (
  typeof globalThis.window !== 'undefined' &&
  typeof globalThis.window.matchMedia !== 'function'
) {
  globalThis.window.matchMedia = () => ({
    matches: false,
    addEventListener() {},
    removeEventListener() {},
  });
}

const emitter = () => jest.fn(() => ({ remove: jest.fn() }));
const resolved = (v) => jest.fn().mockResolvedValue(v);

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
    realPath: resolved({ path: '/home/user' }),
    list: resolved({ entries: [] }),
    stat: resolved(null),
    mkdir: resolved(undefined),
    rename: resolved(undefined),
    remove: resolved(undefined),
    close: resolved(undefined),
  },
}));

jest.mock('./src/specs/NativeRemotlyHerdr', () => ({
  __esModule: true,
  default: {
    exec: resolved({ ok: true, exitCode: 0, stdout: '', stderr: '', code: '', message: '' }),
    subscribe: jest.fn(),
    release: jest.fn(),
    onLine: jest.fn(() => ({ remove: jest.fn() })),
    onStreamEnd: jest.fn(() => ({ remove: jest.fn() })),
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

jest.mock('./src/specs/NativeRemotlySettings', () => ({
  __esModule: true,
  default: {
    get: resolved({
      themeMode: 'system',
      dynamicColor: true,
      terminalFontSize: 14,
      openKeyboardOnTerminal: true,
      showExtraKeyRow: true,
      cursorStyle: 'block',
    }),
    set: resolved(undefined),
    reset: resolved({
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

// Material You is unavailable in the test environment, so the dynamic
// scheme never applies and the default theme is used.
jest.mock('./src/specs/NativeRemotlyDynamicColors', () => ({
  __esModule: true,
  default: {
    get: resolved({ available: false }),
  },
}));

jest.mock('./src/specs/NativeRemotlyCamera', () => ({
  __esModule: true,
  default: {
    openAppSettings: resolved(undefined),
    readClipboard: resolved({ value: '' }),
  },
}));

jest.mock('./src/specs/NativeRemotlyNotify', () => ({
  __esModule: true,
  default: {
    notify: resolved(true),
  },
}));

jest.mock('./src/specs/NativeRemotlyDynamicColors', () => ({
  __esModule: true,
  default: {
    get: resolved({ available: false }),
  },
}));
