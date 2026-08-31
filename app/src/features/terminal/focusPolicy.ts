// When the terminal may open the keyboard.
//
// Kept as a plain object so the rules are testable without a renderer or a
// real IME. useTerminalFocus is the React wrapper around it.
//
// The rule that matters: nothing reopens a keyboard the user dismissed except
// an explicit new request. Focus alone is not a request, because the terminal
// keeps focus through a dismissal.

export interface FocusPolicy {
  /** The terminal reported ready. */
  onReady(): boolean;
  /** A tap or toolbar action. Always focuses. */
  requestFocus(): boolean;
  /** A daemon tab switch. */
  onSessionSwitch(): boolean;
  onKeyboardShown(): void;
  onKeyboardHidden(): void;
  onFocusChange(focused: boolean): void;
  /** Records keyboard state before an overlay; the result restores it. */
  captureForOverlay(): () => boolean;
  isKeyboardVisible(): boolean;
  isFocused(): boolean;
}

export function createFocusPolicy(autoOpen: boolean): FocusPolicy {
  let keyboardVisible = false;
  let focused = false;
  // A first open may focus once, when the user asked for the terminal and the
  // setting allows it. Everything after needs a fresh action.
  let firstOpenPending = autoOpen;

  return {
    onReady(): boolean {
      if (!firstOpenPending) return false;
      firstOpenPending = false;
      return true;
    },

    requestFocus(): boolean {
      firstOpenPending = false;
      return true;
    },

    onSessionSwitch(): boolean {
      // Keeps a visible keyboard across the switch, but never raises a
      // dismissed one.
      return keyboardVisible;
    },

    onKeyboardShown(): void {
      keyboardVisible = true;
    },

    onKeyboardHidden(): void {
      keyboardVisible = false;
      // A deliberate dismissal ends any pending automatic open.
      firstOpenPending = false;
    },

    onFocusChange(next: boolean): void {
      focused = next;
    },

    captureForOverlay(): () => boolean {
      const wasVisible = keyboardVisible;
      // Restoring is only correct when the keyboard was up beforehand.
      // Otherwise closing a host key prompt would raise one unprompted.
      return () => wasVisible;
    },

    isKeyboardVisible: () => keyboardVisible,
    isFocused: () => focused,
  };
}
