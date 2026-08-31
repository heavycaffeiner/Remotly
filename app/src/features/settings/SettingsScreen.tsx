// The Settings destination: appearance, terminal, and notification
// preferences, all persisted through the native settings store.
//
// Every control writes immediately. A failed write rolls the control back and
// says so, rather than leaving the UI showing a value that was not stored.

import React, { useCallback, useEffect, useState } from 'react';
import { Pressable, ScrollView, View } from 'react-native';
import NativeCamera from '../../specs/NativeRemotlyCamera';
import { Screen, IconButton } from '../../components/Screen';
import { Notice, SectionHeader } from '../../components/States';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Toast } from '../../components/Toast';
import { Icon } from '../../components/ui/icon';
import { Segmented } from '../../components/ui/segmented';

/** Offered repeat delays. Short enough to feel instant, long enough to tap. */
const REPEAT_DELAY_CHOICES = [200, 400, 700] as const;
import { Switch } from '../../components/ui/switch';
import { Text } from '../../components/ui/text';
import {
  CURSOR_STYLES,
  MAX_FONT_SIZE,
  MIN_FONT_SIZE,
  type CursorStyle,
  type ThemeMode,
} from '../../lib/settings';
import { getAppInfo, UNKNOWN_APP_INFO, type AppInfo } from '../../lib/appInfo';
import { queryNotificationPermission } from '../../lib/notify';
import { useSettings } from '../../theme/SettingsProvider';
import { onSink, pickFolder } from '../../lib/fileIO';
import { Button } from '../../components/ui/button';

const THEME_LABEL: Record<ThemeMode, string> = {
  system: 'System',
  light: 'Light',
  dark: 'Dark',
};

const CURSOR_LABEL: Record<CursorStyle, string> = {
  block: 'Block',
  bar: 'Bar',
  underline: 'Underline',
};

const SAVE_FAILED = 'That setting could not be saved.';
const NOTIFY_DENIED =
  'Android denied the notification permission, so the switch stayed off.';

export function SettingsScreen(): React.ReactElement {
  const { settings, loadFailed, update, reset } = useSettings();
  const [notice, setNotice] = useState('');
  const [info, setInfo] = useState<AppInfo>(UNKNOWN_APP_INFO);
  const [confirmReset, setConfirmReset] = useState(false);
  const [resetting, setResetting] = useState(false);
  const [notifyBlocked, setNotifyBlocked] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void getAppInfo().then(v => {
      if (!cancelled) setInfo(v);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const apply = useCallback(
    async (patch: Parameters<typeof update>[0]) => {
      const ok = await update(patch);
      if (!ok) setNotice(SAVE_FAILED);
    },
    [update],
  );

  /**
   * Chooses where downloads are saved.
   *
   * The folder is stored when the picker answers, on the same event a download
   * destination arrives on. Holding a folder is what lets a download see an
   * existing file and offer a choice instead of being silently renamed.
   */
  const chooseDownloadFolder = useCallback(() => {
    const off = onSink(f => {
      off();
      if (f.uri === '') return;
      void apply({ downloadFolderUri: f.uri });
    });
    pickFolder().catch(() => {
      off();
      setNotice('Could not open the folder picker.');
    });
  }, [apply]);

  /**
   * The app switch and the OS permission are separate facts, and the switch
   * must not claim to be on when Android will not deliver anything. Turning it
   * on requests the permission first and stays off if that is refused.
   */
  const applyNotify = useCallback(
    async (enabled: boolean) => {
      if (!enabled) {
        setNotifyBlocked(false);
        await apply({ notifyEnabled: false });
        return;
      }
      try {
        const state = await queryNotificationPermission(true);
        if (!state.granted || !state.osEnabled) {
          setNotifyBlocked(true);
          setNotice(NOTIFY_DENIED);
          return;
        }
      } catch {
        setNotifyBlocked(true);
        setNotice(NOTIFY_DENIED);
        return;
      }
      setNotifyBlocked(false);
      await apply({ notifyEnabled: true });
    },
    [apply],
  );

  const fontSize = settings.terminalFontSize;
  const stepFont = useCallback(
    (delta: number) => {
      const next = Math.min(
        MAX_FONT_SIZE,
        Math.max(MIN_FONT_SIZE, settings.terminalFontSize + delta),
      );
      if (next !== settings.terminalFontSize) {
        void apply({ terminalFontSize: next });
      }
    },
    [apply, settings.terminalFontSize],
  );

  const doReset = useCallback(async () => {
    setResetting(true);
    try {
      const ok = await reset();
      setConfirmReset(false);
      setNotice(ok ? 'Settings restored to defaults.' : SAVE_FAILED);
    } finally {
      setResetting(false);
    }
  }, [reset]);

  const openAppSettings = useCallback(() => {
    void NativeCamera.openAppSettings();
  }, []);

  return (
    <Screen title="Settings">
      <ScrollView contentContainerStyle={{ paddingBottom: 32 }}>
        {loadFailed ? (
          <Notice
            tone="danger"
            message="Saved settings could not be read. These are the defaults."
          />
        ) : null}

        <SectionHeader title="Appearance" />
        <View className="gap-2 px-4 py-1">
          <Text variant="callout">Theme</Text>
          <Segmented
            value={settings.themeMode}
            onChange={v => void apply({ themeMode: v })}
            options={(['system', 'light', 'dark'] as const).map(m => ({
              value: m,
              label: THEME_LABEL[m],
              accessibilityLabel: `${THEME_LABEL[m]} theme`,
            }))}
          />
        </View>

        <SettingRow
          title="Dynamic color"
          description="Follow the system color scheme where the device supports it."
        >
          <Switch
            value={settings.dynamicColor}
            accessibilityLabel={`Dynamic color, ${
              settings.dynamicColor ? 'on' : 'off'
            }`}
            onValueChange={v => void apply({ dynamicColor: v })}
          />
        </SettingRow>

        <SectionHeader title="Terminal" />

        <View className="gap-2 px-4 py-1">
          <Text variant="callout">Font size</Text>
          <View className="flex-row items-center gap-3">
            <IconButton
              icon="minus"
              label="Decrease terminal font size"
              disabled={fontSize <= MIN_FONT_SIZE}
              onPress={() => stepFont(-1)}
              className="border border-border"
            />
            <Text
              accessibilityLabel={`Terminal font size, ${fontSize} sp`}
              className="min-w-12 text-center text-base font-medium"
            >
              {`${fontSize} sp`}
            </Text>
            <IconButton
              icon="plus"
              label="Increase terminal font size"
              disabled={fontSize >= MAX_FONT_SIZE}
              onPress={() => stepFont(1)}
              className="border border-border"
            />
          </View>
        </View>

        <View className="gap-2 px-4 py-1">
          <Text variant="callout">Cursor</Text>
          <Segmented
            value={settings.cursorStyle}
            onChange={v => void apply({ cursorStyle: v })}
            options={CURSOR_STYLES.map(style => ({
              value: style,
              label: CURSOR_LABEL[style],
              accessibilityLabel: `${CURSOR_LABEL[style]} cursor`,
            }))}
          />
        </View>

        <SettingRow
          title="Open the keyboard automatically"
          description="Show the keyboard when you open a terminal from a host."
        >
          <Switch
            value={settings.openKeyboardOnTerminal}
            accessibilityLabel={`Open the keyboard automatically, ${
              settings.openKeyboardOnTerminal ? 'on' : 'off'
            }`}
            onValueChange={v => void apply({ openKeyboardOnTerminal: v })}
          />
        </SettingRow>

        <SettingRow
          title="Show the extra key row"
          description="Esc, Tab, Ctrl, Alt, arrows, and symbols above the keyboard."
        >
          <Switch
            value={settings.showExtraKeyRow}
            accessibilityLabel={`Show the extra key row, ${
              settings.showExtraKeyRow ? 'on' : 'off'
            }`}
            onValueChange={v => void apply({ showExtraKeyRow: v })}
          />
        </SettingRow>

        <View className="gap-2 px-4 py-1">
          <Text variant="callout">Hold an extra key to repeat after</Text>
          <Segmented
            value={String(settings.keyRepeatDelayMs)}
            onChange={v => void apply({ keyRepeatDelayMs: Number(v) })}
            options={REPEAT_DELAY_CHOICES.map(ms => ({
              value: String(ms),
              label: `${ms} ms`,
              accessibilityLabel: `Repeat after ${ms} milliseconds`,
            }))}
          />
        </View>

        <SettingRow
          title="Vibrate on key presses"
          description="A short buzz for each extra key, and for a terminal bell."
        >
          <Switch
            value={settings.hapticFeedback}
            accessibilityLabel={`Vibrate on key presses, ${
              settings.hapticFeedback ? 'on' : 'off'
            }`}
            onValueChange={v => void apply({ hapticFeedback: v })}
          />
        </SettingRow>

        <SectionHeader title="Files" />

        <SettingRow
          title="Download folder"
          description={
            settings.downloadFolderUri === ''
              ? 'Not chosen yet. You will be asked on the first download.'
              : folderLabel(settings.downloadFolderUri)
          }
        >
          <Button
            variant="outline"
            size="sm"
            accessibilityLabel="Choose the download folder"
            onPress={chooseDownloadFolder}
          >
            <Text>Change</Text>
          </Button>
        </SettingRow>

        <SectionHeader title="Notifications" />

        <SettingRow
          title="Session notifications"
          description="Notify about bells, output matches, and completed terminal sessions."
        >
          <Switch
            value={settings.notifyEnabled}
            accessibilityLabel={`Session notifications, ${
              settings.notifyEnabled ? 'on' : 'off'
            }`}
            onValueChange={v => void applyNotify(v)}
          />
        </SettingRow>

        {notifyBlocked ? (
          <Notice
            tone="danger"
            message="Android is blocking notifications for this app. Turn them on in system settings, then try again."
            action={{ label: 'Settings', onPress: openAppSettings }}
          />
        ) : null}

        <SectionHeader title="About" />

        <SettingRow
          title="App version"
          description={
            info.versionName !== ''
              ? `${info.versionName} (${info.versionCode})`
              : 'Unavailable'
          }
        />
        <SettingRow
          title="Protocol version"
          description={
            info.protocolVersion !== '' ? info.protocolVersion : 'Unavailable'
          }
        />
        <SettingRow
          title="Android system settings"
          description="Permissions and notification channels for this app."
          icon="cog"
          onPress={openAppSettings}
        />
        <SettingRow
          title="Reset settings"
          description="Restores the defaults on this screen. Hosts, credentials, and saved workspaces are kept."
          icon="rotate-ccw"
          destructive
          onPress={() => setConfirmReset(true)}
        />

        <Text variant="caption" className="px-4 py-3 text-center">
          Settings are stored on this device only.
        </Text>
      </ScrollView>

      <ConfirmDialog
        visible={confirmReset}
        destructive
        busy={resetting}
        title="Reset settings?"
        message="Appearance, terminal, and notification preferences return to their defaults. Your paired hosts, SSH credentials, accepted host keys, and saved workspaces are not affected."
        confirmLabel="Reset"
        onConfirm={() => void doReset()}
        onDismiss={() => setConfirmReset(false)}
      />

      <Toast message={notice} onDismiss={() => setNotice('')} />
    </Screen>
  );
}

interface SettingRowProps {
  title: string;
  description?: string;
  icon?: 'cog' | 'rotate-ccw';
  destructive?: boolean;
  onPress?: () => void;
  children?: React.ReactNode;
}

function SettingRow({
  title,
  description,
  icon,
  destructive = false,
  onPress,
  children,
}: SettingRowProps): React.ReactElement {
  const body = (
    <>
      {icon === undefined ? null : (
        <Icon
          name={icon}
          className={destructive ? 'text-destructive' : 'text-foreground'}
        />
      )}
      <View className="flex-1 gap-0.5">
        <Text className={destructive ? 'text-destructive' : ''}>{title}</Text>
        {description === undefined ? null : (
          <Text variant="caption">{description}</Text>
        )}
      </View>
      {children}
    </>
  );

  if (onPress === undefined) {
    return (
      <View className="min-h-14 flex-row items-center gap-3 px-4 py-3">
        {body}
      </View>
    );
  }
  return (
    <Pressable
      role="button"
      accessibilityLabel={title}
      onPress={onPress}
      className="min-h-14 flex-row items-center gap-3 px-4 py-3 active:bg-accent"
    >
      {body}
    </Pressable>
  );
}

/**
 * A readable name for a tree URI.
 *
 * The document id is the only human-facing part of a content URI, and it is
 * percent-encoded. Decoding fails on a malformed URI, so the raw value is
 * shown rather than nothing.
 */
function folderLabel(treeUri: string): string {
  try {
    const tail = treeUri.split('/').pop() ?? treeUri;
    const decoded = decodeURIComponent(tail);
    const colon = decoded.lastIndexOf(':');
    const path = colon >= 0 ? decoded.slice(colon + 1) : decoded;
    return path === '' ? 'Device storage' : path;
  } catch {
    return treeUri;
  }
}
