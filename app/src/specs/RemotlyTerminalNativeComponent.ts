import { codegenNativeCommands, codegenNativeComponent } from 'react-native';
import type { HostComponent, ViewProps } from 'react-native';
import type {
  DirectEventHandler,
  Int32,
} from 'react-native/Libraries/Types/CodegenTypesNamespace';

// Codegen spec for the Fabric terminal component (RN-06). The native side is a
// SimpleViewManager wrapping the existing TerminalView (libghostty-vt). Bytes
// cross the bridge as base64: `write` takes a base64 string, and the input/
// ptywrite/copy events deliver base64. The JS wrapper (TerminalViewport.tsx)
// is the only place that converts to/from Uint8Array.

type ReadyEvent = Readonly<{ cols: Int32; rows: Int32 }>;
/** A bounded native renderer failure. Never contains terminal contents. */
type ErrorEvent = Readonly<{ code: string }>;
type InputEvent = Readonly<{ data: string }>;
type ResizeEvent = Readonly<{ cols: Int32; rows: Int32 }>;
type SelectionEvent = Readonly<{ active: boolean }>;
type PasteEvent = Readonly<{ target: Int32 }>;
type TitleEvent = Readonly<{ title: string }>;
type PtyWriteEvent = Readonly<{ data: string }>;
// Copy result: `ok` is false when there was no selection. `data` is the copied
// text (base64 is not used here because the selection is already UTF-8 text).
type CopyEvent = Readonly<{ ok: boolean; data: string }>;
// Terminal focus changed. Focus and keyboard visibility are separate facts: a
// view can hold focus with the keyboard hidden.
type FocusEvent = Readonly<{ focused: boolean }>;
type FontSizeEvent = Readonly<{ fontSize: Int32 }>;

export interface NativeProps extends ViewProps {
  /** Opaque session identifier. Bound to the view; never rendered. */
  sessionId?: string | null;
  /** Font size in sp. 0 or omitted uses the shell default. */
  fontSize?: Int32;
  /** 'block' | 'bar' | 'underline'. Omitted uses block. */
  cursorStyle?: string;
  onReady?: DirectEventHandler<ReadyEvent>;
  onError?: DirectEventHandler<ErrorEvent>;
  onInput?: DirectEventHandler<InputEvent>;
  // Named onResizeGrid (not onResize) to avoid colliding with RN internals.
  onResizeGrid?: DirectEventHandler<ResizeEvent>;
  onBell?: DirectEventHandler<{}>;
  onTitle?: DirectEventHandler<TitleEvent>;
  onPtyWrite?: DirectEventHandler<PtyWriteEvent>;
  onCopy?: DirectEventHandler<CopyEvent>;
  onFocusChange?: DirectEventHandler<FocusEvent>;
  onFontSizeChange?: DirectEventHandler<FontSizeEvent>;
  /** A touch selection was made or dropped. */
  onSelectionChange?: DirectEventHandler<SelectionEvent>;
  /** Paste was chosen from the native selection toolbar. */
  onPasteRequest?: DirectEventHandler<PasteEvent>;
}

export default codegenNativeComponent<NativeProps>('RemotlyTerminalView');

interface NativeCommands {
  write: (
    ref: React.ElementRef<HostComponent<NativeProps>>,
    dataB64: string,
  ) => void;
  /** Requests focus and opens the software keyboard. */
  focusTerminal: (ref: React.ElementRef<HostComponent<NativeProps>>) => void;
  /** Hides the software keyboard without dropping terminal focus. */
  hideKeyboard: (ref: React.ElementRef<HostComponent<NativeProps>>) => void;
  selectAll: (ref: React.ElementRef<HostComponent<NativeProps>>) => void;
  copySelection: (ref: React.ElementRef<HostComponent<NativeProps>>) => void;
  /**
   * Scrolls the viewport by whole rows. Negative moves into the scrollback.
   *
   * A no-op while the alternate screen is active: a full-screen application
   * owns its own display and has no scrollback to reveal.
   */
  scrollByRows: (
    ref: React.ElementRef<HostComponent<NativeProps>>,
    rows: Int32,
  ) => void;
  /** Pins the viewport back to the active area. */
  scrollToBottom: (ref: React.ElementRef<HostComponent<NativeProps>>) => void;
  /** Drops the active selection. */
  clearSelection: (ref: React.ElementRef<HostComponent<NativeProps>>) => void;
  /**
   * Drops an open IME preedit without sending it.
   *
   * An extra key acts on the terminal, not on the text being composed, so the
   * overlay is taken down rather than left hanging over the result.
   */
  clearComposition: (ref: React.ElementRef<HostComponent<NativeProps>>) => void;
  /**
   * Resizes the terminal to the size the remote pty has been told.
   *
   * The measurement and the resize are separate because the pty is told on a
   * debounce. Resizing locally first leaves the two ends disagreeing, and
   * anything the application draws in that window is positioned for the grid
   * it still believes in.
   */
  applyRemoteSize: (
    ref: React.ElementRef<HostComponent<NativeProps>>,
    cols: Int32,
    rows: Int32,
  ) => void;
}

export const Commands = codegenNativeCommands<NativeCommands>({
  supportedCommands: [
    'write',
    'focusTerminal',
    'hideKeyboard',
    'selectAll',
    'copySelection',
    'scrollByRows',
    'scrollToBottom',
    'clearSelection',
    'clearComposition',
    'applyRemoteSize',
  ],
});
