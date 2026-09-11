package com.remotly.app.ui.terminal

import androidx.compose.ui.graphics.Color

/**
 * The terminal grid's own surface colours.
 *
 * The grid is not a Material surface: it keeps one background in either
 * theme so a session does not change contrast when the system scheme flips.
 * This is deliberate and must never be replaced with theme colours.
 */
val TerminalBackground = Color(0xFF0B111E)
val TerminalForeground = Color(0xFFE2EBF3)
