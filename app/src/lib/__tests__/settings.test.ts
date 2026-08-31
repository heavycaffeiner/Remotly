import NativeSettings from '../../specs/NativeRemotlySettings';
import {
  DEFAULT_SETTINGS,
  MAX_FONT_SIZE,
  MIN_FONT_SIZE,
  getSettings,
  normalizeSettings,
  resetSettings,
  setSettings,
} from '../settings';

describe('normalizeSettings', () => {
  it('returns the defaults for an empty payload', () => {
    expect(normalizeSettings({})).toEqual(DEFAULT_SETTINGS);
  });

  it('keeps valid values', () => {
    const input = {
      notifyEnabled: true,
      themeMode: 'dark',
      dynamicColor: false,
      terminalFontSize: 18,
      openKeyboardOnTerminal: false,
      showExtraKeyRow: false,
      cursorStyle: 'bar',
      hapticFeedback: false,
      keyRepeatDelayMs: 250,
      downloadFolderUri:
        'content://com.android.providers/tree/primary%3ADownload',
    };
    expect(normalizeSettings(input)).toEqual(input);
  });

  /**
   * The value is handed to the ContentResolver, so anything that is not a
   * content URI is dropped rather than passed through.
   */
  it('rejects a download folder that is not a content uri', () => {
    expect(
      normalizeSettings({ downloadFolderUri: '/sdcard/Download' })
        .downloadFolderUri,
    ).toBe('');
    expect(normalizeSettings({ downloadFolderUri: 42 }).downloadFolderUri).toBe(
      '',
    );
  });

  it('defaults the download folder to unset', () => {
    expect(normalizeSettings({}).downloadFolderUri).toBe('');
  });

  it('bounds the key repeat delay', () => {
    expect(normalizeSettings({ keyRepeatDelayMs: 5 }).keyRepeatDelayMs).toBe(
      150,
    );
    expect(
      normalizeSettings({ keyRepeatDelayMs: 99_999 }).keyRepeatDelayMs,
    ).toBe(1000);
  });

  it('falls back for a key repeat delay that is not a number', () => {
    expect(
      normalizeSettings({ keyRepeatDelayMs: 'fast' }).keyRepeatDelayMs,
    ).toBe(400);
  });

  it('falls back for an unknown theme mode', () => {
    expect(normalizeSettings({ themeMode: 'neon' }).themeMode).toBe('system');
  });

  it('falls back for an unknown cursor style', () => {
    expect(normalizeSettings({ cursorStyle: 'beam' }).cursorStyle).toBe(
      'block',
    );
  });

  it('clamps the font size into range', () => {
    expect(normalizeSettings({ terminalFontSize: 2 }).terminalFontSize).toBe(
      MIN_FONT_SIZE,
    );
    expect(normalizeSettings({ terminalFontSize: 999 }).terminalFontSize).toBe(
      MAX_FONT_SIZE,
    );
  });

  it('rounds a fractional font size', () => {
    expect(normalizeSettings({ terminalFontSize: 14.6 }).terminalFontSize).toBe(
      15,
    );
  });

  it('rejects a non-finite font size', () => {
    expect(normalizeSettings({ terminalFontSize: NaN }).terminalFontSize).toBe(
      DEFAULT_SETTINGS.terminalFontSize,
    );
    expect(
      normalizeSettings({ terminalFontSize: Infinity }).terminalFontSize,
    ).toBe(DEFAULT_SETTINGS.terminalFontSize);
  });

  it('rejects a non-boolean where a boolean is required', () => {
    expect(normalizeSettings({ notifyEnabled: 'yes' }).notifyEnabled).toBe(
      false,
    );
    expect(normalizeSettings({ dynamicColor: 1 }).dynamicColor).toBe(true);
  });

  it('ignores unrelated fields', () => {
    const out = normalizeSettings({ somethingElse: 'x', themeMode: 'light' });
    expect(out.themeMode).toBe('light');
    expect(Object.keys(out).sort()).toEqual(
      Object.keys(DEFAULT_SETTINGS).sort(),
    );
  });
});

describe('bridge boundary', () => {
  it('normalizes what the native side returns', async () => {
    // The stored file survives upgrades and downgrades, so a value that is out
    // of range on the way in is clamped rather than trusted.
    (NativeSettings.get as jest.Mock).mockResolvedValueOnce({
      notifyEnabled: true,
      themeMode: 'neon',
      terminalFontSize: 999,
      cursorStyle: 'beam',
    });
    const out = await getSettings();
    expect(out.themeMode).toBe('system');
    expect(out.cursorStyle).toBe('block');
    expect(out.terminalFontSize).toBe(MAX_FONT_SIZE);
    expect(out.notifyEnabled).toBe(true);
  });

  it('normalizes before writing', async () => {
    (NativeSettings.set as jest.Mock).mockClear();
    await setSettings({ ...DEFAULT_SETTINGS, terminalFontSize: 2 });
    expect(NativeSettings.set).toHaveBeenCalledWith(
      expect.objectContaining({ terminalFontSize: MIN_FONT_SIZE }),
    );
  });

  it('reset resolves with the defaults', async () => {
    const out = await resetSettings();
    expect(out).toEqual(DEFAULT_SETTINGS);
  });

  it('surfaces a store failure as a storage error', async () => {
    (NativeSettings.get as jest.Mock).mockRejectedValueOnce(
      Object.assign(new Error('store unavailable'), { code: '-2' }),
    );
    await expect(getSettings()).rejects.toMatchObject({ kind: 'storage' });
  });
});
