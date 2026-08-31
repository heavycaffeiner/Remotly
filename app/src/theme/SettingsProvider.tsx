// Loads persisted settings before the tree renders and exposes them, plus a
// durable update path, to the whole app.
//
// Settings load once at startup so the first frame already has the right
// theme. Rendering dark and then switching to light is a visible flash, so the
// tree is held behind a neutral placeholder until the load settles.

import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  DEFAULT_SETTINGS,
  getSettings,
  resetSettings,
  setSettings as persistSettings,
  type AppSettings,
} from '../lib/settings';
import { log } from '../lib/log';

export interface SettingsContextValue {
  settings: AppSettings;
  /** False until the persisted settings have been read once. */
  loaded: boolean;
  /**
   * True when the last load failed. The app runs on defaults; the settings
   * screen surfaces this rather than pretending the defaults are the user's.
   */
  loadFailed: boolean;
  /**
   * Merges a partial update and persists it. Resolves false when the write
   * failed, in which case the in-memory value is rolled back.
   */
  update(patch: Partial<AppSettings>): Promise<boolean>;
  /** Restores default preferences. Does not touch hosts or credentials. */
  reset(): Promise<boolean>;
}

const SettingsContext = createContext<SettingsContextValue | null>(null);

export function SettingsProvider({
  children,
  fallback,
}: {
  children: React.ReactNode;
  fallback?: React.ReactNode;
}): React.ReactElement {
  const [settings, setSettings] = useState<AppSettings>(DEFAULT_SETTINGS);
  const [loaded, setLoaded] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const disposed = useRef(false);

  useEffect(() => {
    disposed.current = false;
    void getSettings()
      .then(value => {
        if (disposed.current) return;
        setSettings(value);
        setLoadFailed(false);
      })
      .catch(e => {
        if (disposed.current) return;
        log.error('failed to load settings', { message: String(e) });
        setLoadFailed(true);
      })
      .finally(() => {
        if (!disposed.current) setLoaded(true);
      });
    return () => {
      disposed.current = true;
    };
  }, []);

  // Applies optimistically and rolls back on a failed write, so the control
  // never shows a value that was not stored.
  const update = useCallback(
    async (patch: Partial<AppSettings>): Promise<boolean> => {
      const previous = settings;
      const next = { ...settings, ...patch };
      setSettings(next);
      try {
        // The store takes a whole document, not a patch.
        await persistSettings(next);
        return true;
      } catch (e) {
        log.error('failed to save settings', { message: String(e) });
        if (!disposed.current) setSettings(previous);
        return false;
      }
    },
    [settings],
  );

  const reset = useCallback(async (): Promise<boolean> => {
    const previous = settings;
    setSettings(DEFAULT_SETTINGS);
    try {
      await resetSettings();
      return true;
    } catch (e) {
      log.error('failed to reset settings', { message: String(e) });
      if (!disposed.current) setSettings(previous);
      return false;
    }
  }, [settings]);

  const value = useMemo<SettingsContextValue>(
    () => ({ settings, loaded, loadFailed, update, reset }),
    [settings, loaded, loadFailed, update, reset],
  );

  if (!loaded && fallback !== undefined) return <>{fallback}</>;

  return (
    <SettingsContext.Provider value={value}>
      {children}
    </SettingsContext.Provider>
  );
}

export function useSettings(): SettingsContextValue {
  const value = useContext(SettingsContext);
  if (value === null) {
    throw new Error('useSettings must be used inside a SettingsProvider');
  }
  return value;
}
