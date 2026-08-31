// App-side client for the global settings store (native:
// com.remotly.app.settings.SettingsStore).
//
// Native values are untrusted at this boundary: the file survives upgrades and
// downgrades, so every field is validated and clamped here as well as natively.
import NativeSettings from '../specs/NativeRemotlySettings';
import { makeRemotlyError } from './errors';
import type { RemotlyErrorKind } from './errors';

function bridgeError(code: number, msg: string) {
  const kind: RemotlyErrorKind = 'storage';
  return makeRemotlyError(kind, code, new Error(msg));
}

// Adapts a TurboModule rejection (code is the numeric bridge code as a string)
// to a branded RemotlyError.
function fromRejection(e: unknown) {
  const code = Number((e as { code?: string })?.code ?? 0);
  const msg = (e as Error)?.message ?? 'bridge call failed';
  return bridgeError(code, msg);
}

function toVoid(p: Promise<unknown>): Promise<void> {
  return p.then(
    () => undefined,
    e => {
      throw fromRejection(e);
    },
  );
}

export type ThemeMode = 'system' | 'light' | 'dark';
export type CursorStyle = 'block' | 'bar' | 'underline';

export const THEME_MODES: readonly ThemeMode[] = ['system', 'light', 'dark'];
export const CURSOR_STYLES: readonly CursorStyle[] = [
  'block',
  'bar',
  'underline',
];

export const MIN_FONT_SIZE = 8;
export const MAX_FONT_SIZE = 32;

import {
  clampRepeatDelay,
  REPEAT_DELAY_MS as DEFAULT_KEY_REPEAT_DELAY_MS,
} from '../features/terminal/keyRepeat';

export interface AppSettings {
  /** In-app master switch for terminal event notifications. */
  notifyEnabled: boolean;
  themeMode: ThemeMode;
  /** Use the Android dynamic color scheme where the platform supports it. */
  dynamicColor: boolean;
  /** Terminal font size in sp. */
  terminalFontSize: number;
  /** Open the keyboard when a terminal is opened by explicit user action. */
  openKeyboardOnTerminal: boolean;
  showExtraKeyRow: boolean;
  cursorStyle: CursorStyle;
  /** Vibrate on an extra-key press and on a terminal bell. */
  hapticFeedback: boolean;
  /** How long an extra key is held before it starts repeating, in ms. */
  keyRepeatDelayMs: number;
  /**
   * Content URI of the folder downloads are saved into.
   *
   * Empty until the user picks one. Holding a folder is what lets a download
   * notice an existing file and offer a choice, rather than the system picker
   * quietly renaming around the collision.
   */
  downloadFolderUri: string;
}

export const DEFAULT_SETTINGS: AppSettings = {
  notifyEnabled: false,
  themeMode: 'system',
  dynamicColor: true,
  terminalFontSize: 14,
  openKeyboardOnTerminal: true,
  showExtraKeyRow: true,
  cursorStyle: 'block',
  hapticFeedback: true,
  keyRepeatDelayMs: DEFAULT_KEY_REPEAT_DELAY_MS,
  downloadFolderUri: '',
};

function clampFontSize(value: unknown): number {
  const n = typeof value === 'number' && Number.isFinite(value) ? value : NaN;
  if (Number.isNaN(n)) return DEFAULT_SETTINGS.terminalFontSize;
  return Math.min(MAX_FONT_SIZE, Math.max(MIN_FONT_SIZE, Math.round(n)));
}

function pickBool(value: unknown, fallback: boolean): boolean {
  return typeof value === 'boolean' ? value : fallback;
}

/** Coerces an arbitrary native payload into valid settings. */
export function normalizeSettings(
  raw: Partial<Record<string, unknown>>,
): AppSettings {
  const themeMode = THEME_MODES.find(m => m === raw.themeMode);
  const cursorStyle = CURSOR_STYLES.find(c => c === raw.cursorStyle);
  return {
    notifyEnabled: pickBool(raw.notifyEnabled, DEFAULT_SETTINGS.notifyEnabled),
    themeMode: themeMode ?? DEFAULT_SETTINGS.themeMode,
    dynamicColor: pickBool(raw.dynamicColor, DEFAULT_SETTINGS.dynamicColor),
    terminalFontSize: clampFontSize(raw.terminalFontSize),
    openKeyboardOnTerminal: pickBool(
      raw.openKeyboardOnTerminal,
      DEFAULT_SETTINGS.openKeyboardOnTerminal,
    ),
    showExtraKeyRow: pickBool(
      raw.showExtraKeyRow,
      DEFAULT_SETTINGS.showExtraKeyRow,
    ),
    cursorStyle: cursorStyle ?? DEFAULT_SETTINGS.cursorStyle,
    hapticFeedback: pickBool(
      raw.hapticFeedback,
      DEFAULT_SETTINGS.hapticFeedback,
    ),
    keyRepeatDelayMs: clampRepeatDelay(
      typeof raw.keyRepeatDelayMs === 'number'
        ? raw.keyRepeatDelayMs
        : DEFAULT_SETTINGS.keyRepeatDelayMs,
    ),
    // A content URI, opaque to this layer. Anything else is dropped rather
    // than passed to the ContentResolver.
    downloadFolderUri:
      typeof raw.downloadFolderUri === 'string' &&
      raw.downloadFolderUri.startsWith('content://')
        ? raw.downloadFolderUri
        : DEFAULT_SETTINGS.downloadFolderUri,
  };
}

export function getSettings(): Promise<AppSettings> {
  return NativeSettings.get().then(
    d => normalizeSettings(d as unknown as Record<string, unknown>),
    e => {
      throw fromRejection(e);
    },
  );
}

export function setSettings(settings: AppSettings): Promise<void> {
  const next = normalizeSettings(
    settings as unknown as Record<string, unknown>,
  );
  return toVoid(NativeSettings.set(next));
}

/**
 * Restores the default preferences.
 *
 * Preferences only: paired hosts, SSH credentials, accepted host keys, and
 * saved workspaces are not touched.
 */
export function resetSettings(): Promise<AppSettings> {
  return NativeSettings.reset().then(
    d => normalizeSettings(d as unknown as Record<string, unknown>),
    e => {
      throw fromRejection(e);
    },
  );
}
