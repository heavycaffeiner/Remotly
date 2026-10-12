package com.remotly.app.settings

import com.remotly.app.terminal.TerminalTheme
import com.remotly.app.terminal.parseTerminalColor
import com.remotly.app.terminal.terminalColorHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Raw text survives invalid intermediate edits and leaving the settings screen. */
data class TerminalThemeDraft(val values: List<String>) {
    init { require(values.size == 22) }
    fun edit(index: Int, value: String): TerminalThemeDraft = copy(values = values.mapIndexed { i, old -> if (i == index) value else old })
    fun resolve(): TerminalTheme {
        val colors = values.map(::parseTerminalColor)
        return TerminalTheme(colors[0], colors[1], colors[2], colors[3], colors[4], colors[5], colors.drop(6))
    }
    companion object {
        fun from(theme: TerminalTheme) = TerminalThemeDraft(
            (listOf(theme.background, theme.foreground, theme.cursor, theme.cursorText,
                theme.selectionBackground, theme.selectionForeground) + theme.ansi).map(::terminalColorHex),
        )
    }
}

object TerminalThemeDraftState {
    private val _draft = MutableStateFlow<TerminalThemeDraft?>(null)
    val draft = _draft.asStateFlow()
    fun begin(theme: TerminalTheme) { if (_draft.value == null) _draft.value = TerminalThemeDraft.from(theme) }
    fun replace(theme: TerminalTheme) { _draft.value = TerminalThemeDraft.from(theme) }
    fun edit(index: Int, value: String) { _draft.value = _draft.value?.edit(index, value) }
    fun cancel() { _draft.value = null }
}
