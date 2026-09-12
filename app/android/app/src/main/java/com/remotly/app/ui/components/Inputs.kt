package com.remotly.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * The app's text field: filled, rounded, no indicator line.
 *
 * Takes the same slots as the Material field so a caller only swaps the name.
 */
@Composable
fun RemotlyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        minLines = minLines,
        shape = MaterialTheme.shapes.small,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            errorContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
    )
}

/** Chip colours with a tonal fill instead of an outline. */
@Composable
fun tonalChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    labelColor = MaterialTheme.colorScheme.onSurface,
    iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
)

/**
 * One choice among a few, as a row of tonal chips.
 *
 * Replaces the segmented button, whose outline is the only line it draws.
 * The chips are announced as radio buttons so a screen reader hears one
 * group rather than unrelated toggles.
 */
@Composable
fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((value, label) in options) {
            val active = value == selected
            FilterChip(
                selected = active,
                onClick = { onSelected(value) },
                label = { Text(label) },
                border = null,
                colors = tonalChipColors(),
                leadingIcon = if (active) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
                modifier = Modifier.semantics { role = Role.RadioButton },
            )
        }
    }
}

/** The same safe exit for host forms and inline naming dialogs. */
@Composable
fun DiscardChangesDialog(onDiscard: () -> Unit, onKeepEditing: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text("Discard changes?") },
        text = { Text("Your unsaved changes will be lost.") },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text("Discard", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onKeepEditing) { Text("Keep editing") } },
    )
}
