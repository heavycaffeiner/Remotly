// React wrapper around the keyboard policy. The rules live in focusPolicy.ts
// and are tested there.

import { useCallback, useEffect, useMemo, useRef } from 'react';
import { Keyboard } from 'react-native';
import { createFocusPolicy } from './focusPolicy';

export interface TerminalFocusOptions {
  /** Moves focus to the terminal and shows the IME. */
  focus: () => void;
  /**
   * True when this terminal was opened by an explicit user action and the
   * `openKeyboardOnTerminal` setting is on.
   */
  autoOpen: boolean;
}

export interface TerminalFocusHandle {
  /** The terminal is ready. Focuses only for a permitted first open. */
  onReady: () => void;
  /** A tap or a toolbar action. Always focuses. */
  requestFocus: () => void;
  /** A session switch. Keeps a visible keyboard; never raises a dismissed one. */
  onSessionSwitch: () => void;
  /** Native focus changed. */
  onNativeFocusChange: (focused: boolean) => void;
  /**
   * Records keyboard state before an overlay opens. The returned function
   * restores focus only when the keyboard was visible.
   */
  captureForOverlay: () => () => void;
  isKeyboardVisible: () => boolean;
  isFocused: () => boolean;
}

export function useTerminalFocus({
  focus,
  autoOpen,
}: TerminalFocusOptions): TerminalFocusHandle {
  const focusRef = useRef(focus);
  focusRef.current = focus;

  // Created once: the policy carries the dismissal state, which must survive
  // re-renders.
  const policy = useMemo(() => createFocusPolicy(autoOpen), [autoOpen]);

  useEffect(() => {
    const show = Keyboard.addListener('keyboardDidShow', () => {
      policy.onKeyboardShown();
    });
    const hide = Keyboard.addListener('keyboardDidHide', () => {
      policy.onKeyboardHidden();
    });
    return () => {
      show.remove();
      hide.remove();
    };
  }, [policy]);

  const requestFocus = useCallback(() => {
    if (policy.requestFocus()) focusRef.current();
  }, [policy]);

  const onReady = useCallback(() => {
    if (policy.onReady()) focusRef.current();
  }, [policy]);

  const onSessionSwitch = useCallback(() => {
    if (policy.onSessionSwitch()) focusRef.current();
  }, [policy]);

  const onNativeFocusChange = useCallback(
    (next: boolean) => policy.onFocusChange(next),
    [policy],
  );

  const captureForOverlay = useCallback(() => {
    const restore = policy.captureForOverlay();
    return () => {
      if (restore()) focusRef.current();
    };
  }, [policy]);

  return {
    onReady,
    requestFocus,
    onSessionSwitch,
    onNativeFocusChange,
    captureForOverlay,
    isKeyboardVisible: () => policy.isKeyboardVisible(),
    isFocused: () => policy.isFocused(),
  };
}
