package com.remotly.app.ui.terminal

import com.remotly.app.settings.ExtraKeyVisibility

@Suppress("UNUSED_PARAMETER")
fun extraKeysVisible(
    policy: ExtraKeyVisibility,
    terminalFocused: Boolean,
    imeVisible: Boolean,
    hardwareKeyboardAttached: Boolean,
    keyboardExplicitlyRequested: Boolean = false,
    temporarilyRequested: Boolean = false,
): Boolean = when {
    temporarilyRequested -> true
    policy == ExtraKeyVisibility.Always -> true
    policy == ExtraKeyVisibility.Hidden -> false
    else -> terminalFocused && (imeVisible || keyboardExplicitlyRequested)
}
