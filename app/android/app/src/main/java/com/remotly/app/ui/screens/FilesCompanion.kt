package com.remotly.app.ui.screens

import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tab
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.*
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.remotly.app.ui.companionFits

@Composable
fun FilesCompanion(
    filesSelected: Boolean,
    requested: Boolean,
    onFilesFocus: () -> Unit,
    modifier: Modifier = Modifier,
    terminal: @Composable (Modifier) -> Unit,
    files: (@Composable (Modifier) -> Unit)?,
    onClose: () -> Unit = {},
    onDock: () -> Unit = {},
) {
    var fraction by rememberSaveable { mutableFloatStateOf(0.65f) }
    var resizeFocused by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val sideBySide = requested && files != null && companionFits(maxWidth.value, maxHeight.value)
        val width = maxWidth.value
        val usableWidth = (width - 8f).coerceAtLeast(1f)
        val effectiveFraction = if (sideBySide) fraction.coerceIn(560f / usableWidth, 1f - 320f / usableWidth) else 1f
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(effectiveFraction).fillMaxHeight()) {
                terminal(Modifier.fillMaxSize())
                if (!sideBySide && filesSelected && files != null) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                        .onFocusChanged { if (it.hasFocus) onFilesFocus() }.focusGroup()) {
                        FilesPaneHeader(onClose = onClose)
                        files(Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
            if (sideBySide) {
                VerticalDivider(Modifier.width(8.dp).fillMaxHeight()
                    .onFocusChanged { resizeFocused = it.isFocused }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                            Key.DirectionLeft -> { fraction = (effectiveFraction - 0.05f).coerceAtLeast(560f / usableWidth); true }
                            Key.DirectionRight -> { fraction = (effectiveFraction + 0.05f).coerceAtMost(1f - 320f / usableWidth); true }
                            else -> false
                        }
                    }.focusable().pointerInput(width) {
                    detectHorizontalDragGestures { change, amount ->
                        change.consume()
                        val delta = with(density) { amount.toDp().value } / usableWidth
                        fraction = (effectiveFraction + delta).coerceIn(560f / usableWidth, 1f - 320f / usableWidth)
                    }
                }.semantics {
                    contentDescription = "Resize terminal and Files panes"
                    stateDescription = "Terminal ${(effectiveFraction * 100).toInt()} percent"
                    customActions = listOf(
                        CustomAccessibilityAction("Wider terminal") { fraction = (effectiveFraction + 0.05f).coerceAtMost(1f - 320f / usableWidth); true },
                        CustomAccessibilityAction("Wider Files") { fraction = (effectiveFraction - 0.05f).coerceAtLeast(560f / usableWidth); true },
                    )
                }, color = if (resizeFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.weight(1f - effectiveFraction).fillMaxHeight()
                    .onFocusChanged { if (it.hasFocus) onFilesFocus() }.focusGroup()) {
                    FilesPaneHeader(onClose = onClose, onDock = onDock)
                    files(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun FilesPaneHeader(onClose: () -> Unit, onDock: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("Files", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (onDock != null) {
            IconButton(onClick = onDock) { Icon(Icons.Default.Tab, "Use Files as a tab") }
        }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close Files pane") }
    }
}
