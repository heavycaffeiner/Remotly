package com.remotly.app.terminal

import kotlin.math.pow

/** Resolved, opaque colors. ANSI entries configure only indices 0 through 15. */
data class TerminalTheme(
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val cursorText: Int,
    val selectionBackground: Int,
    val selectionForeground: Int,
    val ansi: List<Int>,
) {
    init {
        require(ansi.size == 16) { "ANSI palette must contain sixteen colors" }
        require((listOf(background, foreground, cursor, cursorText, selectionBackground, selectionForeground) + ansi)
            .all { it ushr 24 == 255 }) { "Terminal colors must be opaque" }
    }

    val contrast: Double get() = contrastRatio(background, foreground)
    fun nativeColors(): IntArray = (listOf(background, foreground, cursor) + ansi).toIntArray()
}

fun parseTerminalColor(value: String): Int {
    require(Regex("#[0-9a-fA-F]{6}").matches(value)) { "Use #RRGGBB" }
    return (0xff shl 24) or value.substring(1).toInt(16)
}

fun terminalColorHex(value: Int): String = "#%06X".format(java.util.Locale.ROOT, value and 0xffffff)

fun contrastRatio(a: Int, b: Int): Double {
    fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val v = (color ushr shift and 255) / 255.0
            return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
    val x = luminance(a)
    val y = luminance(b)
    return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
}

data class TerminalThemePreference(
    val id: String = "classic",
    val custom: TerminalTheme = TerminalThemes.Classic,
) {
    fun resolve(): TerminalTheme = if (id == "custom") custom else TerminalThemes.presets[id] ?: TerminalThemes.Classic
    fun normalized(): TerminalThemePreference = if (id == "custom" || id in TerminalThemes.presets) this else copy(id = "classic")
}

object TerminalThemes {
    private fun preset(bg: String, fg: String, selection: String, colors: String): TerminalTheme =
        TerminalTheme(
            parseTerminalColor(bg), parseTerminalColor(fg), parseTerminalColor(fg), parseTerminalColor(bg),
            parseTerminalColor(selection), parseTerminalColor(fg),
            colors.split(' ').map { parseTerminalColor("#$it") },
        )

    // Captured from the pinned native render state: black background, white text,
    // and Ghostty's default base16 palette. Selection keeps the old inverse pair.
    val Classic = preset("#000000", "#FFFFFF", "#FFFFFF",
        "1D1F21 CC6666 B5BD68 F0C674 81A2BE B294BB 8ABEB7 C5C8C6 666666 D54E53 B9CA4A E7C547 7AA6DA C397D8 70C0B1 EAEAEA")
        .copy(selectionForeground = parseTerminalColor("#000000"))
    val Nord = preset("#2E3440", "#D8DEE9", "#434C5E",
        "3B4252 BF616A A3BE8C EBCB8B 81A1C1 B48EAD 88C0D0 E5E9F0 4C566A BF616A A3BE8C EBCB8B 81A1C1 B48EAD 8FBCBB ECEFF4")
    val Dracula = preset("#282A36", "#F8F8F2", "#44475A",
        "000000 FF5555 50FA7B F1FA8C BD93F9 FF79C6 8BE9FD BFBFBF 4D4D4D FF6E67 5AF78E F4F99D CAA9FA FF92D0 9AEDFE E6E6E6")
    val CatppuccinMocha = preset("#1E1E2E", "#CDD6F4", "#F5E0DC",
        "45475A F38BA8 A6E3A1 F9E2AF 89B4FA F5C2E7 94E2D5 BAC2DE 585B70 F38BA8 A6E3A1 F9E2AF 89B4FA F5C2E7 94E2D5 A6ADC8").copy(cursor = parseTerminalColor("#F5E0DC"), selectionForeground = parseTerminalColor("#1E1E2E"))
    private const val SOLARIZED = "073642 DC322F 859900 B58900 268BD2 D33682 2AA198 EEE8D5 002B36 CB4B16 586E75 657B83 839496 6C71C4 93A1A1 FDF6E3"
    val SolarizedDark = preset("#002B36", "#839496", "#073642", SOLARIZED)
    // Canonical #657B83 gives 4.13:1 on #FDF6E3. #586E75 gives 4.99:1.
    val SolarizedLight = preset("#FDF6E3", "#586E75", "#EEE8D5", SOLARIZED)
    val presets = linkedMapOf(
        "classic" to Classic, "nord" to Nord, "dracula" to Dracula,
        "catppuccin-mocha" to CatppuccinMocha, "solarized-dark" to SolarizedDark,
        "solarized-light" to SolarizedLight,
    )
    val labels = linkedMapOf(
        "classic" to "Classic", "nord" to "Nord", "dracula" to "Dracula",
        "catppuccin-mocha" to "Catppuccin Mocha", "solarized-dark" to "Solarized Dark",
        "solarized-light" to "Solarized Light", "custom" to "Custom",
    )
}
