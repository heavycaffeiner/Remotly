// The terminal scaffold shared by daemon workspaces and SSH sessions.
//
// This layer knows about raw bytes and viewport size. It deliberately knows
// nothing about daemon channel ids or SSH host ids: the owning screen supplies
// a send function and the terminal supplies bytes back.

import React, {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from 'react';
import { View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import {
  TerminalViewport,
  type TerminalViewportHandle,
} from '../../components/TerminalViewport';
import { TerminalKeyRow } from './TerminalKeyRow';
import { TerminalToolbar, type TerminalMenuAction } from './TerminalToolbar';
import {
  TerminalStatusBanner,
  type TerminalBannerTone,
} from './TerminalStatusBanner';
import { applyModifier, transformKey, type ModifierKey } from './terminalInput';
import { useTerminalFocus } from './useTerminalFocus';
import { useTerminalResize, type GridSize } from './useTerminalResize';
import { SwipePager } from '../../components/SwipePager';
import { useKeyboardOverlap } from '../../components/KeyboardLifted';
import { Button } from '../../components/ui/button';
import { Text } from '../../components/ui/text';
import { Toast } from '../../components/Toast';
import type { IconName } from '../../components/ui/icon';
import NativeCamera from '../../specs/NativeRemotlyCamera';

export interface TerminalBanner {
  tone: TerminalBannerTone;
  message: string;
  action?: { label: string; onPress: () => void };
}

export interface TerminalScreenProps {
  title: string;
  subtitle?: string;
  onBack: () => void;
  /** Sends user input bytes to the session. */
  onSend: (bytes: Uint8Array) => void;
  /** Sends a new grid size to the session. Already debounced and deduped. */
  onResize: (size: GridSize) => void;
  /** Identifies the live session. Changing it resets resize state. */
  sessionKey: string;
  /** Bound to the native view; never rendered. */
  sessionId?: string;
  fontSize: number;
  cursorStyle?: 'block' | 'bar' | 'underline';
  /** Focus the terminal on first ready. */
  autoOpenKeyboard: boolean;
  showKeyRow: boolean;
  /** Delay before a held extra key starts repeating, in ms. */
  keyRepeatDelayMs: number;
  /** Vibrate on an extra-key press. */
  haptics: boolean;
  banner?: TerminalBanner | null;
  toolbarActions: readonly TerminalMenuAction[];
  toolbarPrimary?: {
    icon: IconName;
    label: string;
    onPress: () => void;
    disabled?: boolean;
  };
  /** Rendered instead of the terminal, for connecting and failed states. */
  overlay?: React.ReactNode;
  /**
   * Rendered in place of the terminal for a tab that is not a shell.
   *
   * Unlike overlay this is the tab's real content, so it keeps the swipe and
   * the tab strip and does not suppress the keyboard the way a transient
   * connecting state does.
   */
  pane?: React.ReactNode;
  /** Session tabs. Both daemon workspaces and SSH hosts supply one. */
  tabStrip?: React.ReactNode;
  /**
   * Moves to the previous or next session. A horizontal swipe across the
   * terminal calls this; omit it where there is nothing to move between.
   */
  onSwitchSession?: (direction: -1 | 1) => void;
  /** Position in the tab strip, so the switch animates in the right
   *  direction. Omit where there is only one session. */
  sessionIndex?: number;
  /** The terminal is attached and sized, and can accept bytes. */
  onReady?: (size: GridSize) => void;
  onBell?: () => void;
  onTitle?: (title: string) => void;
  /** Persists a font size selected with a pinch gesture. */
  onFontSizeChange?: (fontSize: number) => void;
}

export interface TerminalScreenHandle {
  write(bytes: Uint8Array): Promise<void>;
  focus(): void;
  selectAll(): Promise<void>;
  copy(): Promise<string | null>;
  /** Scrolls the scrollback by whole rows. Negative moves toward the top. */
  scrollByRows(rows: number): void;
  /** Sends the clipboard's text to the session as ordinary input. */
  paste(): void;
  /** Pins the viewport back to the active area. */
  scrollToBottom(): void;
  /** Sends the pending size now, after a keyboard or rotation change. */
  flushResize(): void;
  /** Keeps focus across a session switch without reopening the keyboard. */
  handleSessionSwitch(): void;
  /** Hides the keyboard without dropping terminal focus. */
  hideKeyboard(): void;
  /**
   * Records keyboard state before an overlay opens. The returned function
   * restores it only when it was visible.
   */
  captureForOverlay(): () => void;
}

export const TerminalScreen = forwardRef<
  TerminalScreenHandle,
  TerminalScreenProps
>(function TerminalScaffold(props, ref): React.ReactElement {
  const {
    title,
    subtitle,
    onBack,
    onSend,
    onResize,
    sessionKey,
    sessionId,
    fontSize,
    cursorStyle,
    autoOpenKeyboard,
    showKeyRow,
    keyRepeatDelayMs,
    haptics,
    banner,
    toolbarActions,
    toolbarPrimary,
    overlay,
    pane,
    tabStrip,
    onSwitchSession,
    sessionIndex,
    onReady,
    onBell,
    onTitle,
    onFontSizeChange,
  } = props;

  const insets = useSafeAreaInsets();
  const viewport = useRef<TerminalViewportHandle | null>(null);
  const [modifier, setModifier] = useState<ModifierKey | null>(null);
  const [notice, setNotice] = useState('');
  const [gridSize, setGridSize] = useState<GridSize | null>(null);
  const [rendererAttempt, setRendererAttempt] = useState(0);
  const [rendererReady, setRendererReady] = useState(false);
  const [rendererError, setRendererError] = useState<string | null>(null);
  // API 35+ edge-to-edge can leave an adjustResize window at its full height.
  // In that case the IME overlays the native terminal and hides the key row.
  const { overlap: imeInset, onLayout: handleRootLayout } =
    useKeyboardOverlap();
  const [hasSelection, setHasSelection] = useState(false);
  const bottomSpacerStyle = useMemo(
    () => ({ height: imeInset > 0 ? 0 : insets.bottom }),
    [imeInset, insets.bottom],
  );

  // The terminal is resized only once the pty has been told, so the two ends
  // never disagree about the grid. Resizing locally on measurement instead
  // leaves a window in which the application is still drawing for its old
  // size, and its absolute moves and scroll regions land in the wrong band.
  const sendResize = useCallback(
    (size: GridSize) => {
      onResize(size);
      void viewport.current
        ?.applyRemoteSize(size.cols, size.rows)
        .catch(() => undefined);
    },
    [onResize],
  );

  const resize = useTerminalResize({ send: sendResize, sessionKey });

  const focusTerminal = useCallback(() => {
    viewport.current?.focus().catch(() => undefined);
  }, []);

  const focusPolicy = useTerminalFocus({
    focus: focusTerminal,
    autoOpen: autoOpenKeyboard,
  });

  // Committed input carries the latched modifier, then clears it. The latch is
  // owned here so the key row's visual state cannot drift from it.
  // The latched modifier and the send target are read through refs so this
  // callback is stable. A new function identity per keystroke re-renders the
  // whole terminal subtree and re-registers the native event handler, which is
  // felt directly as input lag while typing.
  const modifierRef = useRef(modifier);
  modifierRef.current = modifier;
  const sendRef = useRef(onSend);
  sendRef.current = onSend;

  const handleInput = useCallback((bytes: Uint8Array) => {
    const result = applyModifier(bytes, modifierRef.current);
    if (result.clearModifier) setModifier(null);
    if (result.notice !== undefined) setNotice(result.notice);
    sendRef.current(result.bytes);
  }, []);

  /**
   * Answers a query the running program made of the terminal.
   *
   * A shell's startup and any prompt framework probe the terminal for its
   * device attributes and colours, and the reply has to go back up the pty.
   * The terminal produced these replies and nothing forwarded them, so the
   * program sat waiting for an answer that never came and the bytes it had
   * already written showed up on screen as stray characters.
   *
   * Not passed through the modifier latch: this is the terminal replying to a
   * program, not the user typing.
   */
  const handlePtyWrite = useCallback((bytes: Uint8Array) => {
    sendRef.current(bytes);
  }, []);

  const handleKey = useCallback((key: string) => {
    const result = transformKey(key, modifierRef.current);
    if (result === null) return;
    // An extra key acts on the terminal, not on the text being composed, so
    // the preedit overlay comes down rather than sitting over the result.
    void viewport.current?.clearComposition().catch(() => undefined);
    if (result.clearModifier) setModifier(null);
    if (result.notice !== undefined) setNotice(result.notice);
    sendRef.current(result.bytes);
  }, []);

  const handleModifier = useCallback((next: ModifierKey) => {
    void viewport.current?.clearComposition().catch(() => undefined);
    setModifier(current => (current === next ? null : next));
  }, []);

  const handleReady = useCallback(
    (info: { cols: number; rows: number }) => {
      setRendererReady(true);
      setRendererError(null);
      const size = { cols: info.cols, rows: info.rows };
      setGridSize(size);
      onReady?.(size);
      focusPolicy.onReady();
    },
    [focusPolicy, onReady],
  );

  const handleRendererError = useCallback((code: string) => {
    setRendererError(code);
  }, []);

  const handleFontSizeChange = useCallback(
    (next: number) => {
      setNotice(`Terminal text: ${next} sp`);
      onFontSizeChange?.(next);
    },
    [onFontSizeChange],
  );

  // A session switch remounts the viewport, so its readiness has to be
  // remeasured. Leaving this true would leave the new view's startup
  // unwatched, and leaving a stale error would show a failure for a renderer
  // that was replaced.
  useEffect(() => {
    setRendererReady(false);
    setRendererError(null);
    setGridSize(null);
  }, [sessionKey]);

  // Native startup must be bounded. Start this only while the actual viewport
  // is mounted: a connection dialog or another deliberate overlay is not a
  // renderer failure.
  useEffect(() => {
    if (
      overlay != null ||
      pane != null ||
      rendererReady ||
      rendererError !== null
    ) {
      return undefined;
    }
    const timer = setTimeout(() => setRendererError('startup_timeout'), 5000);
    return () => clearTimeout(timer);
  }, [overlay, pane, rendererAttempt, rendererError, rendererReady, sessionKey]);

  const retryRenderer = useCallback(() => {
    setGridSize(null);
    setRendererReady(false);
    setRendererError(null);
    setRendererAttempt(current => current + 1);
  }, []);

  const rendererFailure = rendererError ? (
    <View
      accessibilityLiveRegion="assertive"
      className="items-center gap-4 rounded-lg bg-card p-6"
    >
      <Text variant="title" className="text-center">
        Terminal is unavailable
      </Text>
      <Text variant="muted" className="text-center">
        The terminal renderer did not start. Your remote session is still safe.
      </Text>
      <Button onPress={retryRenderer}>
        <Text>Retry terminal</Text>
      </Button>
    </View>
  ) : null;

  const handleResize = useCallback(
    (size: GridSize) => {
      setGridSize(size);
      resize.report(size);
    },
    [resize],
  );

  // The selection itself is Android's: the native view runs a floating
  // ActionMode with Copy, so this only tracks whether one is up. A swipe must
  // not steal the gesture while the user is adjusting a handle.
  const handleSelectionChange = useCallback((active: boolean) => {
    setHasSelection(active);
  }, []);

  // Pasted text is sent as ordinary input, so the shell sees it exactly as if
  // it had been typed. Nothing is added: a trailing newline in the clipboard
  // submits the line, which is what the user copied.
  const paste = useCallback(() => {
    void NativeCamera.readClipboard()
      .then(result => {
        const text = result.value ?? '';
        if (text === '') {
          setNotice('The clipboard is empty');
          return;
        }
        onSend(new TextEncoder().encode(text));
      })
      .catch(() => setNotice('Could not read the clipboard'));
  }, [onSend]);

  useImperativeHandle(
    ref,
    () => ({
      async write(bytes: Uint8Array) {
        await viewport.current?.write(bytes);
      },
      focus() {
        focusPolicy.requestFocus();
      },
      async selectAll() {
        await viewport.current?.selectAll();
      },
      async copy() {
        const result = await viewport.current?.copy();
        return result ?? null;
      },
      scrollByRows(rows: number) {
        void viewport.current?.scrollByRows(rows).catch(() => undefined);
      },
      scrollToBottom() {
        void viewport.current?.scrollToBottom().catch(() => undefined);
      },
      flushResize() {
        resize.flush();
      },
      paste() {
        paste();
      },
      handleSessionSwitch() {
        focusPolicy.onSessionSwitch();
      },
      hideKeyboard() {
        void viewport.current?.hideKeyboard().catch(() => undefined);
      },
      captureForOverlay() {
        return focusPolicy.captureForOverlay();
      },
    }),
    [focusPolicy, resize, paste],
  );

  return (
    <View
      onLayout={handleRootLayout}
      style={{ paddingBottom: imeInset }}
      className="flex-1 bg-terminal"
    >
      <TerminalToolbar
        title={title}
        {...(subtitle ? { subtitle } : {})}
        onBack={onBack}
        {...(toolbarPrimary && pane == null
          ? { primaryAction: toolbarPrimary }
          : {})}
        actions={toolbarActions}
      />

      {tabStrip}

      {/* The native view is explicitly clipped here. Without a clipping owner
          it can compose over sibling app chrome on some Android render paths. */}
      <View className="flex-1 overflow-hidden" collapsable={false}>
        <View className="flex-1 bg-terminal" collapsable={false}>
          {banner ? (
            <TerminalStatusBanner
              tone={banner.tone}
              message={banner.message}
              {...(banner.action ? { action: banner.action } : {})}
            />
          ) : null}

          {rendererFailure ?? overlay ? (
            <View className="flex-1 items-center justify-center p-4">
              {rendererFailure ?? overlay}
            </View>
          ) : pane !== undefined && pane !== null ? (
            <SwipePager
              pageKey={sessionKey}
              {...(sessionIndex === undefined
                ? {}
                : { pageIndex: sessionIndex })}
              {...(onSwitchSession === undefined
                ? {}
                : { onSwitch: onSwitchSession })}
            >
              {pane}
            </SwipePager>
          ) : (
            // The native view owns the tap: it distinguishes a one-finger tap
            // from a pinch and calls performClick itself, which a JS responder
            // wrapped around the Fabric leaf could not do reliably.
            <SwipePager
              pageKey={sessionKey}
              {...(sessionIndex === undefined
                ? {}
                : { pageIndex: sessionIndex })}
              {...(onSwitchSession === undefined
                ? {}
                : { onSwitch: onSwitchSession })}
              disabled={hasSelection}
            >
              <TerminalViewport
                // Keyed by session as well as attempt. Without the session in
                // the key, switching tabs reused the same native view and its
                // terminal, so a second shell rendered the first one's screen.
                key={`${sessionKey}:${rendererAttempt}`}
                ref={viewport}
                {...(sessionId === undefined ? {} : { sessionId })}
                fontSize={fontSize}
                {...(cursorStyle === undefined ? {} : { cursorStyle })}
                onReady={handleReady}
                onError={handleRendererError}
                onInput={handleInput}
                onPtyWrite={handlePtyWrite}
                onResize={handleResize}
                onFocusChange={focusPolicy.onNativeFocusChange}
                onFontSizeChange={handleFontSizeChange}
                onSelectionChange={handleSelectionChange}
                onPasteRequest={paste}
                {...(onBell ? { onBell } : {})}
                {...(onTitle ? { onTitle } : {})}
              />
            </SwipePager>
          )}

          {__DEV__ && gridSize !== null ? (
            <Text
              accessibilityElementsHidden
              importantForAccessibility="no-hide-descendants"
              className="absolute bottom-2 right-2 text-xs text-terminal-foreground opacity-60"
            >
              {`${gridSize.cols}x${gridSize.rows}`}
            </Text>
          ) : null}
        </View>
      </View>

      {showKeyRow && pane == null ? (
        <TerminalKeyRow
          onKey={handleKey}
          onModifier={handleModifier}
          activeModifier={modifier}
          repeatDelayMs={keyRepeatDelayMs}
          haptics={haptics}
        />
      ) : null}
      {/* Gboard already owns the bottom navigation inset while it is visible.
          Keeping this spacer then leaves a dead strip between it and the key
          row, so reserve it only when no IME overlap is present. */}
      <View style={bottomSpacerStyle} />

      <Toast message={notice} onDismiss={() => setNotice('')} />
    </View>
  );
});
