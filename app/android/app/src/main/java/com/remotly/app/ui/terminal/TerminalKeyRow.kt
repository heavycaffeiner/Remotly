package com.remotly.app.ui.terminal

// The extra terminal key row.
//
// One row, scrolled horizontally. Wrapping to a second row would change the
// terminal's height and make the remote pty resize unpredictably, so the row
// height is fixed and overflow scrolls instead.
//
// The active modifier is owned by the caller. Latching it here would let the
// visual state drift once the caller has already consumed and cleared it.

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long a touch waits before it counts as a key press.
 *
 * A horizontal drag is read as the start of a scroll within this window, so
 * the key under the finger never fires: [androidx.compose.foundation.gestures.PressGestureScope.tryAwaitRelease]
 * returns false the moment an ancestor (the scrolling row) claims the
 * gesture. A touch that is still down once the window elapses is read as a
 * deliberate hold instead, at which point it commits and streams; one that
 * lifts inside the window fires once as a plain tap.
 */
private const val PRESS_DELAY_MS = 80L

/** Every key keeps at least this touch target, in either dimension. */
private val KEY_MIN_SIZE = 48.dp

private data class KeyDef(
    val key: String,
    val label: String,
    val text: String? = null,
    val icon: ImageVector? = null,
    val modifier: ModifierKey? = null,
    /** Holding this key streams it, the way a physical keyboard does. */
    val repeats: Boolean = false,
)

private val KEYS: List<KeyDef> = listOf(
    KeyDef(key = "esc", label = "Escape", text = "Esc"),
    KeyDef(key = "tab", label = "Tab", text = "Tab"),
    KeyDef(key = "ctrl", label = "Control", text = "Ctrl", modifier = ModifierKey.CTRL),
    KeyDef(key = "alt", label = "Alt", text = "Alt", modifier = ModifierKey.ALT),
    KeyDef(key = "slash", label = "Slash", text = "/"),
    KeyDef(key = "pipe", label = "Pipe", text = "|"),
    KeyDef(key = "backslash", label = "Backslash", text = "\\"),
    KeyDef(key = "left", label = "Arrow left", icon = Icons.Filled.KeyboardArrowLeft, repeats = true),
    KeyDef(key = "down", label = "Arrow down", icon = Icons.Filled.KeyboardArrowDown, repeats = true),
    KeyDef(key = "up", label = "Arrow up", icon = Icons.Filled.KeyboardArrowUp, repeats = true),
    KeyDef(key = "right", label = "Arrow right", icon = Icons.Filled.KeyboardArrowRight, repeats = true),
    KeyDef(key = "home", label = "Home", text = "Home"),
    KeyDef(key = "end", label = "End", text = "End"),
    KeyDef(key = "pageup", label = "Page up", text = "PgUp", repeats = true),
    KeyDef(key = "pagedown", label = "Page down", text = "PgDn", repeats = true),
)

/**
 * The scrollable strip of extra keys shown above the keyboard.
 *
 * @param onKey a non-modifier key press, by logical key name.
 * @param onModifier a modifier press. The caller decides whether to latch or
 *   clear it.
 * @param activeModifier the modifier currently latched by the caller, if any.
 * @param repeatDelayMs delay before a held key starts repeating.
 * @param haptics whether to give haptic feedback on each key.
 * @param onKeyboard opens the software keyboard. Pinned beside the scrolling
 *   keys so it stays reachable however far the strip has been scrolled.
 */
@Composable
fun TerminalKeyRow(
    onKey: (String) -> Unit,
    onModifier: (ModifierKey) -> Unit,
    activeModifier: ModifierKey?,
    repeatDelayMs: Int,
    haptics: Boolean,
    onKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onKeyState = rememberUpdatedState(onKey)

    // One repeater for the whole row. A per-key repeater cannot enforce that
    // only one key is held: a finger sliding from one key to the next, or the
    // scrolling row claiming the touch, would leave the first key's release
    // unfired and two streams running at once.
    val repeater = remember {
        KeyRepeater(fire = { key -> onKeyState.value(key) }, initialDelayMs = repeatDelayMs)
    }
    LaunchedEffect(repeatDelayMs) { repeater.setDelay(repeatDelayMs) }
    // A row torn down mid-hold must not keep firing into a screen that is
    // gone.
    DisposableEffect(Unit) { onDispose { repeater.stop() } }

    val haptic = LocalHapticFeedback.current

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier,
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(56.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    for (def in KEYS) {
                        KeyButton(
                            def = def,
                            active = def.modifier != null && def.modifier == activeModifier,
                            onKeyDown = {
                                if (haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                when {
                                    def.modifier != null -> {
                                        repeater.stop()
                                        onModifier(def.modifier)
                                    }
                                    def.repeats -> repeater.press(def.key)
                                    else -> onKey(def.key)
                                }
                            },
                            onKeyUp = {
                                if (def.repeats) repeater.release(def.key)
                            },
                        )
                    }
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                IconButton(
                    onClick = onKeyboard,
                    modifier = Modifier.defaultMinSize(minWidth = KEY_MIN_SIZE, minHeight = KEY_MIN_SIZE),
                ) {
                    Icon(Icons.Filled.Keyboard, contentDescription = "Show the keyboard")
                }
            }
        }
    }
}

@Composable
private fun KeyButton(
    def: KeyDef,
    active: Boolean,
    onKeyDown: () -> Unit,
    onKeyUp: () -> Unit,
) {
    val backgroundColor =
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val contentColor =
        if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
    val borderColor =
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val shape = RoundedCornerShape(14.dp)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .defaultMinSize(minWidth = KEY_MIN_SIZE, minHeight = KEY_MIN_SIZE)
            .clip(shape)
            .background(backgroundColor)
            .border(1.dp, borderColor, shape)
            .clearAndSetSemantics {
                contentDescription = def.label
                role = Role.Button
                // A latched modifier reports as selected rather than only
                // looking it, so the state is never carried by colour alone.
                if (def.modifier != null) selected = active
                onClick(label = "Press") {
                    onKeyDown()
                    onKeyUp()
                    true
                }
            }
            .pointerInput(def.key) {
                detectTapGestures(
                    onPress = {
                        // Committed only once the disambiguation window
                        // passes without the row claiming the gesture as a
                        // scroll. tryAwaitRelease returning false here means
                        // exactly that: an ancestor consumed the touch.
                        val liftedEarly = withTimeoutOrNull(PRESS_DELAY_MS) { tryAwaitRelease() }
                        when (liftedEarly) {
                            null -> {
                                // Still down once the window elapsed: a
                                // deliberate hold, not a scroll.
                                onKeyDown()
                                tryAwaitRelease()
                                onKeyUp()
                            }
                            true -> {
                                // Released inside the window: a genuine,
                                // quick tap.
                                onKeyDown()
                                onKeyUp()
                            }
                            false -> {
                                // Cancelled: the row started scrolling
                                // instead. Nothing fires.
                            }
                        }
                    },
                )
            }
            .padding(horizontal = 12.dp),
    ) {
        val icon = def.icon
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor)
        } else {
            Text(text = def.text ?: def.label, color = contentColor, style = MaterialTheme.typography.labelLarge)
        }
    }
}
