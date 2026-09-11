package com.remotly.app.ui.terminal

/**
 * When the terminal may open the keyboard.
 *
 * The rule that matters: nothing reopens a keyboard the user dismissed except
 * an explicit new request. Focus alone is not a request, because the
 * terminal keeps focus through a dismissal.
 */
class FocusPolicy(autoOpen: Boolean) {
    private var keyboardVisible = false
    private var focused = false

    // A first open may focus once, when the user asked for the terminal and
    // the setting allows it. Everything after needs a fresh action.
    private var firstOpenPending = autoOpen

    /** The terminal reported ready. Returns whether it should now focus. */
    fun onReady(): Boolean {
        if (!firstOpenPending) return false
        firstOpenPending = false
        return true
    }

    /** A tap or toolbar action. Always focuses. */
    fun requestFocus(): Boolean {
        firstOpenPending = false
        return true
    }

    /** A tab switch. Returns whether the keyboard should stay open. */
    fun onSessionSwitch(): Boolean =
        // Keeps a visible keyboard across the switch, but never raises a
        // dismissed one.
        keyboardVisible

    fun onKeyboardShown() {
        keyboardVisible = true
    }

    fun onKeyboardHidden() {
        keyboardVisible = false
        // A deliberate dismissal ends any pending automatic open.
        firstOpenPending = false
    }

    fun onFocusChange(next: Boolean) {
        focused = next
    }

    /** Records keyboard state before an overlay; the result restores it. */
    fun captureForOverlay(): () -> Boolean {
        val wasVisible = keyboardVisible
        // Restoring is only correct when the keyboard was up beforehand.
        // Otherwise closing a host key prompt would raise one unprompted.
        return { wasVisible }
    }

    fun isKeyboardVisible(): Boolean = keyboardVisible

    fun isFocused(): Boolean = focused
}
