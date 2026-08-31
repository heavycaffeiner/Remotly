import { createFocusPolicy } from '../focusPolicy';

// The rule under test: nothing reopens a keyboard the user dismissed except an
// explicit new request. The old implementation focused on every ready and
// active transition, so a dismissed keyboard sprang back on its own.
describe('createFocusPolicy', () => {
  describe('first open', () => {
    it('focuses on ready when auto-open is enabled', () => {
      const p = createFocusPolicy(true);
      expect(p.onReady()).toBe(true);
    });

    it('does not focus on ready when auto-open is disabled', () => {
      const p = createFocusPolicy(false);
      expect(p.onReady()).toBe(false);
    });

    it('focuses only once', () => {
      // A remount or a second ready must not raise the keyboard again.
      const p = createFocusPolicy(true);
      expect(p.onReady()).toBe(true);
      expect(p.onReady()).toBe(false);
    });

    it('is cancelled by a dismissal before ready arrives', () => {
      const p = createFocusPolicy(true);
      p.onKeyboardShown();
      p.onKeyboardHidden();
      expect(p.onReady()).toBe(false);
    });
  });

  describe('explicit request', () => {
    it('always focuses', () => {
      const p = createFocusPolicy(false);
      expect(p.requestFocus()).toBe(true);
    });

    it('focuses after the user dismissed the keyboard', () => {
      // The reported symptom: tapping after a dismissal did nothing.
      const p = createFocusPolicy(true);
      p.onKeyboardShown();
      p.onKeyboardHidden();
      expect(p.requestFocus()).toBe(true);
    });

    it('focuses repeatedly across many dismiss cycles', () => {
      const p = createFocusPolicy(true);
      for (let i = 0; i < 20; i += 1) {
        p.onKeyboardShown();
        p.onKeyboardHidden();
        expect(p.requestFocus()).toBe(true);
      }
    });

    it('focuses even while the terminal already holds focus', () => {
      // Focus is not keyboard visibility. This is exactly the state where a
      // requestFocus-only implementation fails.
      const p = createFocusPolicy(true);
      p.onFocusChange(true);
      p.onKeyboardHidden();
      expect(p.isFocused()).toBe(true);
      expect(p.requestFocus()).toBe(true);
    });

    it('consumes the pending first open', () => {
      const p = createFocusPolicy(true);
      expect(p.requestFocus()).toBe(true);
      expect(p.onReady()).toBe(false);
    });
  });

  describe('session switch', () => {
    it('keeps a visible keyboard', () => {
      const p = createFocusPolicy(true);
      p.onKeyboardShown();
      expect(p.onSessionSwitch()).toBe(true);
    });

    it('does not raise a dismissed keyboard', () => {
      const p = createFocusPolicy(true);
      p.onKeyboardShown();
      p.onKeyboardHidden();
      expect(p.onSessionSwitch()).toBe(false);
    });

    it('does not raise a keyboard that was never shown', () => {
      const p = createFocusPolicy(false);
      expect(p.onSessionSwitch()).toBe(false);
    });
  });

  describe('overlay capture', () => {
    it('restores a keyboard that was visible', () => {
      const p = createFocusPolicy(true);
      p.onKeyboardShown();
      const restore = p.captureForOverlay();
      // The dialog takes focus and the keyboard goes away.
      p.onKeyboardHidden();
      expect(restore()).toBe(true);
    });

    it('does not restore a keyboard that was hidden', () => {
      // Closing a host key prompt must not raise a keyboard the user never
      // asked for.
      const p = createFocusPolicy(true);
      p.onKeyboardHidden();
      const restore = p.captureForOverlay();
      expect(restore()).toBe(false);
    });

    it('captures the state at open time, not at close time', () => {
      const p = createFocusPolicy(true);
      const restore = p.captureForOverlay();
      p.onKeyboardShown();
      expect(restore()).toBe(false);
    });

    it('supports nested overlays independently', () => {
      const p = createFocusPolicy(true);
      p.onKeyboardShown();
      const outer = p.captureForOverlay();
      p.onKeyboardHidden();
      const inner = p.captureForOverlay();
      expect(inner()).toBe(false);
      expect(outer()).toBe(true);
    });
  });

  describe('state tracking', () => {
    it('tracks keyboard visibility', () => {
      const p = createFocusPolicy(false);
      expect(p.isKeyboardVisible()).toBe(false);
      p.onKeyboardShown();
      expect(p.isKeyboardVisible()).toBe(true);
      p.onKeyboardHidden();
      expect(p.isKeyboardVisible()).toBe(false);
    });

    it('tracks focus separately from keyboard visibility', () => {
      const p = createFocusPolicy(false);
      p.onFocusChange(true);
      p.onKeyboardHidden();
      // Focused, keyboard hidden: a real and common state.
      expect(p.isFocused()).toBe(true);
      expect(p.isKeyboardVisible()).toBe(false);
    });
  });
});
