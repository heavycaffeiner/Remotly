package com.remotly.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotly.app.settings.SettingsState
import com.remotly.app.settings.TerminalThemeDraftState
import com.remotly.app.terminal.TerminalPreviewView
import com.remotly.app.terminal.TerminalTheme
import com.remotly.app.terminal.TerminalThemePreference
import com.remotly.app.terminal.TerminalThemes

@Composable
fun TerminalThemePreview(theme: TerminalTheme) {
    AndroidView(
        factory = { TerminalPreviewView(it) },
        update = { it.applyTheme(theme) },
        modifier = Modifier.fillMaxWidth().height(168.dp).clipToBounds(),
    )
}

@Composable
fun TerminalThemeEditor(onClose: () -> Unit) {
    val draft by TerminalThemeDraftState.draft.collectAsStateWithLifecycle()
    val settings by SettingsState.settings.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf(settings.terminalTheme.custom) }
    var validation by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun requestClose() { if (!saving) confirmDiscard = true }
    val current = draft ?: return
    val parsed = runCatching { current.resolve() }
    AlertDialog(
        onDismissRequest = ::requestClose,
        title = { Text("Custom terminal theme") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TerminalThemePreview(preview)
                Row {
                    TextButton(onClick = {
                        parsed.onSuccess { preview = it; validation = null }
                            .onFailure { validation = it.message }
                    }) { Text("Preview") }
                }
                Text("Start from", style = MaterialTheme.typography.labelLarge)
                TerminalThemes.presets.forEach { (id, theme) ->
                    TextButton(onClick = { TerminalThemeDraftState.replace(theme) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(TerminalThemes.labels.getValue(id))
                    }
                }
                val labels = listOf("Background", "Foreground", "Cursor", "Cursor text", "Selection background", "Selection foreground") +
                    (0..15).map { "ANSI $it" }
                current.values.forEachIndexed { index, value ->
                    OutlinedTextField(
                        value = value,
                        onValueChange = { TerminalThemeDraftState.edit(index, it) },
                        label = { Text(labels[index]) },
                        singleLine = true,
                        isError = runCatching { com.remotly.app.terminal.parseTerminalColor(value) }.isFailure,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                parsed.getOrNull()?.let { theme ->
                    if (theme.contrast < 4.5) Text("Text contrast below 4.5:1", color = MaterialTheme.colorScheme.error)
                }
                validation?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = parsed.isSuccess && !saving, onClick = {
                val theme = parsed.getOrThrow()
                saving = true
                scope.launch {
                    val saved = SettingsState.saveChange { it.copy(terminalTheme = TerminalThemePreference("custom", theme)) }
                    saving = false
                    if (saved) { TerminalThemeDraftState.cancel(); onClose() }
                    else validation = "Cannot save theme. Your draft is still open."
                }
            }) { Text(if (saving) "Saving" else "Apply") }
        },
        dismissButton = {
            TextButton(onClick = ::requestClose, enabled = !saving) { Text("Cancel") }
        },
    )
    if (confirmDiscard) com.remotly.app.ui.components.DiscardChangesDialog(
        onDiscard = { TerminalThemeDraftState.cancel(); onClose() },
        onKeepEditing = { confirmDiscard = false },
    )
}
