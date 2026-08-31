// The app-side mount point and lifecycle owner for the native terminal component.
//
// RN-06: this renders the Fabric `RemotlyTerminalView` (SimpleViewManager over the
// existing TerminalView, libghostty-vt). The prop surface and the imperative
// handle are the app half of the terminal module boundary: the transport calls
// `write` with session output and consumes `onInput`/`onPtyWrite` to drive a
// session.
//
// No terminal emulation, I/O, or security behavior happens in this module. The
// native component is the only thing that touches a pty or a transport. Raw
// bytes cross the bridge as base64 (src/lib/base64.ts); this module decodes and
// encodes at the boundary so callers only ever see Uint8Array.
import React, {
  forwardRef,
  useCallback,
  useImperativeHandle,
  useRef,
} from 'react';
import { StyleSheet } from 'react-native';
import type { HostComponent } from 'react-native';

import RemotlyTerminalNativeComponent, {
  Commands,
  type NativeProps,
} from '../specs/RemotlyTerminalNativeComponent';
import { decodeBase64, encodeBase64 } from '../lib/base64';

type TerminalInstance = React.ElementRef<HostComponent<NativeProps>>;

/** Imperative surface the transport uses to drive the terminal. */
export interface TerminalViewportHandle {
  /** Feed raw output bytes from the session into the terminal. */
  write(bytes: Uint8Array): Promise<void>;
  /** Move focus to the terminal and show the IME. */
  focus(): Promise<void>;
  /** Hide the IME without dropping terminal focus. */
  hideKeyboard(): Promise<void>;
  /** Select all terminal text. */
  selectAll(): Promise<void>;
  /** Copy the current selection. Resolves to the text, or null when none. */
  copy(): Promise<string | null>;
  /**
   * Scroll the viewport by whole rows. Negative moves into the scrollback.
   *
   * A no-op while a full-screen application owns the alternate screen, which
   * has no scrollback to reveal.
   */
  scrollByRows(rows: number): Promise<void>;
  /** Pin the viewport back to the active area. */
  scrollToBottom(): Promise<void>;
  /** Drop the active selection. */
  clearSelection(): Promise<void>;
  /** Drops an open IME preedit without sending it. */
  clearComposition(): Promise<void>;
  /** Resizes the terminal to the size the remote pty has been told. */
  applyRemoteSize(cols: number, rows: number): Promise<void>;
}

export interface TerminalViewportProps {
  /** Opaque session identifier. Bound to the native view; never rendered. */
  sessionId?: string;
  /** Font size in sp. Omit to use the shell default. */
  fontSize?: number;
  /** Cursor shape. Omit to use the block cursor. */
  cursorStyle?: 'block' | 'bar' | 'underline';
  /** Invoked once the terminal view is attached and sized. */
  onReady?: (info: { sessionId: string; cols: number; rows: number }) => void;
  /** Invoked when the native renderer cannot be created. */
  onError?: (code: string) => void;
  /** Invoked with user input to forward to the session. */
  onInput?: (bytes: Uint8Array) => void;
  /** Invoked when the grid is resized so the session can match. */
  onResize?: (info: { cols: number; rows: number }) => void;
  /** Invoked on a terminal bell. */
  onBell?: () => void;
  /** Invoked when the running program changes the terminal title. */
  onTitle?: (title: string) => void;
  /** Invoked with terminal pty output. */
  onPtyWrite?: (bytes: Uint8Array) => void;
  /**
   * Invoked when terminal focus changes.
   *
   * Focus is not keyboard visibility: the terminal can stay focused while the
   * user has dismissed the keyboard.
   */
  onFocusChange?: (focused: boolean) => void;
  /** Invoked after a pinch settles on a new whole-sp font size. */
  onFontSizeChange?: (fontSize: number) => void;
  /** Invoked when a touch selection is made or dropped. */
  onSelectionChange?: (active: boolean) => void;
  /** Invoked when Paste is chosen from the native selection toolbar. */
  onPasteRequest?: () => void;
}

export const TerminalViewport = forwardRef<
  TerminalViewportHandle,
  TerminalViewportProps
>(function TerminalViewportInner(props, ref) {
  const {
    sessionId,
    fontSize,
    cursorStyle,
    onReady,
    onError,
    onInput,
    onResize,
    onBell,
    onTitle,
    onPtyWrite,
    onFocusChange,
    onFontSizeChange,
    onSelectionChange,
    onPasteRequest,
  } = props;
  const elementRef = useRef<TerminalInstance | null>(null);
  const copyResolverRef = useRef<((text: string | null) => void) | null>(null);

  const handleReady = useCallback(
    (e: { nativeEvent: { cols: number; rows: number } }) => {
      onReady?.({
        sessionId: sessionId ?? '',
        cols: e.nativeEvent.cols,
        rows: e.nativeEvent.rows,
      });
    },
    [onReady, sessionId],
  );

  const handleError = useCallback(
    (e: { nativeEvent: { code: string } }) => {
      const code = e.nativeEvent.code;
      onError?.(
        typeof code === 'string' && code.length > 0 && code.length <= 64
          ? code
          : 'renderer_failed',
      );
    },
    [onError],
  );

  const handleInput = useCallback(
    (e: { nativeEvent: { data: string } }) => {
      try {
        onInput?.(decodeBase64(e.nativeEvent.data));
      } catch (err) {
        console.warn('failed to decode terminal input', err);
      }
    },
    [onInput],
  );

  const handleResize = useCallback(
    (e: { nativeEvent: { cols: number; rows: number } }) => {
      onResize?.({ cols: e.nativeEvent.cols, rows: e.nativeEvent.rows });
    },
    [onResize],
  );

  const handleBell = useCallback(() => {
    onBell?.();
  }, [onBell]);

  const handleTitle = useCallback(
    (e: { nativeEvent: { title: string } }) => {
      onTitle?.(e.nativeEvent.title);
    },
    [onTitle],
  );

  const handlePtyWrite = useCallback(
    (e: { nativeEvent: { data: string } }) => {
      try {
        onPtyWrite?.(decodeBase64(e.nativeEvent.data));
      } catch (err) {
        console.warn('failed to decode terminal pty output', err);
      }
    },
    [onPtyWrite],
  );

  const handleFocusChange = useCallback(
    (e: { nativeEvent: { focused: boolean } }) => {
      onFocusChange?.(e.nativeEvent.focused === true);
    },
    [onFocusChange],
  );

  const handleSelectionChange = useCallback(
    (e: { nativeEvent: { active: boolean } }) => {
      onSelectionChange?.(e.nativeEvent.active === true);
    },
    [onSelectionChange],
  );

  const handlePasteRequest = useCallback(() => {
    onPasteRequest?.();
  }, [onPasteRequest]);

  const handleFontSizeChange = useCallback(
    (e: { nativeEvent: { fontSize: number } }) => {
      const next = Math.round(e.nativeEvent.fontSize);
      if (Number.isFinite(next) && next >= 8 && next <= 32) {
        onFontSizeChange?.(next);
      }
    },
    [onFontSizeChange],
  );

  // The copy command cannot return a value, so the native side reports the
  // result through this event; the pending resolver picks it up.
  const handleCopy = useCallback(
    (e: { nativeEvent: { ok: boolean; data: string } }) => {
      const resolve = copyResolverRef.current;
      if (resolve != null) {
        copyResolverRef.current = null;
        resolve(e.nativeEvent.ok ? e.nativeEvent.data : null);
      }
    },
    [],
  );

  useImperativeHandle(
    ref,
    () => {
      const el = () => elementRef.current;
      return {
        async write(bytes: Uint8Array) {
          const node = el();
          if (node == null) throw new Error('terminal is not attached');
          Commands.write(node, encodeBase64(bytes));
        },
        async focus() {
          const node = el();
          if (node == null) throw new Error('terminal is not attached');
          Commands.focusTerminal(node);
        },
        async hideKeyboard() {
          const node = el();
          if (node == null) throw new Error('terminal is not attached');
          Commands.hideKeyboard(node);
        },
        async selectAll() {
          const node = el();
          if (node == null) throw new Error('terminal is not attached');
          Commands.selectAll(node);
        },
        copy(): Promise<string | null> {
          const node = el();
          if (node == null)
            return Promise.reject(new Error('terminal is not attached'));
          return new Promise<string | null>(resolve => {
            copyResolverRef.current = resolve;
            Commands.copySelection(node);
          });
        },
        async scrollByRows(rows: number) {
          const node = el();
          if (node == null) throw new Error('terminal is not attached');
          Commands.scrollByRows(node, Math.trunc(rows));
        },
        async scrollToBottom() {
          const node = el();
          if (node == null) throw new Error('terminal is not attached');
          Commands.scrollToBottom(node);
        },
        async clearSelection() {
          const node = el();
          if (node == null) throw new Error('terminal is not attached');
          Commands.clearSelection(node);
        },
        async clearComposition() {
          const node = el();
          if (node == null) throw new Error('terminal is not attached');
          Commands.clearComposition(node);
        },
        async applyRemoteSize(cols: number, rows: number) {
          const node = el();
          if (node == null) throw new Error('terminal is not attached');
          Commands.applyRemoteSize(node, cols, rows);
        },
      };
    },
    [],
  );

  return (
    <RemotlyTerminalNativeComponent
      ref={elementRef}
      accessibilityRole="text"
      accessibilityLabel="Terminal output"
      style={styles.fill}
      {...(sessionId === undefined ? {} : { sessionId })}
      {...(fontSize === undefined ? {} : { fontSize })}
      {...(cursorStyle === undefined ? {} : { cursorStyle })}
      onReady={handleReady}
      onError={handleError}
      onInput={handleInput}
      onResizeGrid={handleResize}
      onBell={handleBell}
      onTitle={handleTitle}
      onPtyWrite={handlePtyWrite}
      onCopy={handleCopy}
      onFocusChange={handleFocusChange}
      onFontSizeChange={handleFontSizeChange}
      onSelectionChange={handleSelectionChange}
      onPasteRequest={handlePasteRequest}
    />
  );
});

const styles = StyleSheet.create({
  fill: { flex: 1 },
});
