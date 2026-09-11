package com.remotly.app.ui.screens

// The chrome shared by every terminal screen: a compact top bar, an optional
// tab strip, the clipped terminal body with its banner overlay, and the
// extra key row.
//
// This file owns none of a session's identity or connection state. It knows
// only how to draw the surrounding chrome and forward the gestures a screen
// wires to its own store. The terminal itself is the caller's content slot,
// because only the caller can attach its own gesture layer (a swipe, a mux
// double tap) directly to the box the terminal sits in.

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remotly.app.ui.components.EmptyState
import com.remotly.app.ui.components.ErrorState
import com.remotly.app.ui.components.ScreenAction
import com.remotly.app.ui.terminal.ModifierKey
import com.remotly.app.ui.terminal.REPEAT_DELAY_MS
import com.remotly.app.ui.terminal.TerminalKeyRow
import kotlinx.coroutines.launch

/** Height of the compact top bar. Every dp here is a terminal row the user does not get. */
private val BAR_HEIGHT = 40.dp

/** Touch target every icon-only control meets, however tall its visual row is. */
private val TOUCH_TARGET = 48.dp

/** Height of one pill in the tab strip. Tall enough on its own to need no touch-target trick. */
private val TAB_HEIGHT = 48.dp

/**
 * How a tab in [TerminalScaffold]'s strip is doing. Drives the icon shown
 * beside its label and the suffix announced after it, so the phase is never
 * carried by colour alone.
 */
enum class TerminalTabPhase { Connecting, Active, Ended, Gone }

/** One entry in the shared terminal tab strip. */
data class TerminalTab(
    val id: String,
    val label: String,
    val phase: TerminalTabPhase,
)

enum class TerminalBannerTone { Info, Busy, Error }

/**
 * A transient connection banner drawn over the terminal body.
 *
 * It overlays rather than occupying layout height: a banner that comes and
 * goes in the layout flow changes the terminal's measured grid, which
 * resizes the remote pty every time the connection state changes.
 */
data class TerminalBanner(
    val tone: TerminalBannerTone,
    val message: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/**
 * Replaces the terminal body entirely: there is no session to show, or
 * nothing has connected yet.
 *
 * An [icon] renders it with [EmptyState]; without one it renders with
 * [ErrorState], whose single retry-shaped action fits a fatal failure better
 * than an empty list does.
 */
data class TerminalFailure(
    val icon: ImageVector? = null,
    val title: String,
    val message: String? = null,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/**
 * The chrome shared by every terminal screen.
 *
 * [content] receives a [Modifier] already sized to fill the clipped terminal
 * body; the caller chains its own gesture modifiers onto it before handing it
 * to its `TerminalPane`. It is shown only while [failure] is null.
 *
 * When [showKeyRow] is false the bar grows a "Show the keyboard" action wired
 * to [onKeyboard], because the key row's own pinned keyboard button is what
 * makes the keyboard reachable once dismissed; without the row, the bar is
 * the only other way back to it.
 */
@Composable
fun TerminalScaffold(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    onMenu: (() -> Unit)? = null,
    actions: List<ScreenAction> = emptyList(),
    onActionsMenuOpen: () -> Unit = {},
    tabs: List<TerminalTab> = emptyList(),
    activeTabId: String? = null,
    onSelectTab: (String) -> Unit = {},
    onCloseTab: (String) -> Unit = {},
    onRenameTab: ((String) -> Unit)? = null,
    onAddTab: (() -> Unit)? = null,
    canAddTab: Boolean = true,
    banner: TerminalBanner? = null,
    failure: TerminalFailure? = null,
    showKeyRow: Boolean = false,
    activeModifier: ModifierKey? = null,
    onKey: (String) -> Unit = {},
    onModifier: (ModifierKey) -> Unit = {},
    keyRepeatDelayMs: Int = REPEAT_DELAY_MS,
    haptics: Boolean = false,
    onKeyboard: () -> Unit = {},
    // Drawn over the terminal, for a tab that shows something else. The
    // terminal underneath stays composed because it is what measures the
    // grid every session is opened against.
    pane: (@Composable () -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    // Edge to edge is on for the window, so this consumes the system bars
    // and the keyboard itself. Without it the bar draws under the status bar
    // and, worse, the key row lands under the navigation bar, where the
    // system takes the touches and the keys do nothing at all.
    Column(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        TerminalTopBar(
            title = title,
            subtitle = subtitle,
            onBack = onBack,
            onMenu = onMenu,
            actions = actions,
            onActionsMenuOpen = onActionsMenuOpen,
            showKeyboardAction = !showKeyRow && pane == null,
            onKeyboard = onKeyboard,
        )

        if (tabs.isNotEmpty()) {
            TerminalTabStrip(
                tabs = tabs,
                activeTabId = activeTabId,
                onSelect = onSelectTab,
                onClose = onCloseTab,
                onRename = onRenameTab,
                onAdd = onAddTab,
                canAdd = canAddTab,
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds(),
        ) {
            // The viewport stays composed under the failure card rather than
            // being swapped out for it. It is what measures the grid, and a
            // screen that waits for a real grid before opening its first
            // session would never get one if the card could unmount it.
            content(Modifier.fillMaxSize())
            if (pane != null) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { pane() }
            }
            if (failure != null) {
                TerminalFailureCard(failure)
            }
            if (banner != null) {
                TerminalBannerOverlay(banner, modifier = Modifier.align(Alignment.TopCenter))
            }
        }

        if (showKeyRow && pane == null) {
            TerminalKeyRow(
                onKey = onKey,
                onModifier = onModifier,
                activeModifier = activeModifier,
                repeatDelayMs = keyRepeatDelayMs,
                haptics = haptics,
                onKeyboard = onKeyboard,
            )
        }
    }
}

@Composable
private fun TerminalTopBar(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    onMenu: (() -> Unit)?,
    actions: List<ScreenAction>,
    onActionsMenuOpen: () -> Unit,
    showKeyboardAction: Boolean,
    onKeyboard: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        tonalElevation = 0.dp,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BAR_HEIGHT)
                    .padding(horizontal = 4.dp),
            ) {
                if (onMenu != null) {
                    CompactIconAction(icon = Icons.Filled.Menu, label = "Workspaces and tabs", onClick = onMenu)
                }
                CompactIconAction(icon = Icons.AutoMirrored.Filled.ArrowBack, label = "Back", onClick = onBack)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                ) {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (showKeyboardAction) {
                    CompactIconAction(icon = Icons.Filled.Keyboard, label = "Show the keyboard", onClick = onKeyboard)
                }
                if (actions.isNotEmpty()) {
                    CompactIconAction(
                        icon = Icons.Filled.MoreVert,
                        label = "Terminal actions",
                        onClick = {
                            onActionsMenuOpen()
                            menuOpen = true
                        },
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        for ((index, action) in actions.withIndex()) {
                            if (action.destructive && index > 0 && !actions[index - 1].destructive) {
                                HorizontalDivider()
                            }
                            DropdownMenuItem(
                                text = { Text(action.title) },
                                enabled = action.enabled,
                                leadingIcon = {
                                    Icon(
                                        action.icon,
                                        contentDescription = null,
                                        tint = if (action.destructive) MaterialTheme.colorScheme.error else defaultActionTint(),
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    action.onClick()
                                },
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** The overflow menu item's default tint, matching ordinary (non-destructive) menu text. */
@Composable
private fun defaultActionTint() = MaterialTheme.colorScheme.onSurface

/**
 * An icon-only control with a real touch target of at least [TOUCH_TARGET],
 * even inside a bar shorter than that. [requiredSize] ignores the bar's own
 * height constraint rather than being compressed by it; Compose hit-tests
 * each node's actual placed bounds, so the extra reach works even though it
 * draws past the bar's edge.
 */
@Composable
private fun CompactIconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .requiredSize(TOUCH_TARGET)
            .clip(CircleShape)
            .combinedClickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) defaultActionTint() else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun iconFor(phase: TerminalTabPhase): ImageVector? = when (phase) {
    TerminalTabPhase.Active -> null
    TerminalTabPhase.Connecting -> Icons.Filled.Schedule
    TerminalTabPhase.Ended -> Icons.Filled.StopCircle
    TerminalTabPhase.Gone -> Icons.Filled.LinkOff
}

private fun suffixFor(phase: TerminalTabPhase): String = when (phase) {
    TerminalTabPhase.Active -> ""
    TerminalTabPhase.Connecting -> ", connecting"
    TerminalTabPhase.Ended -> ", ended"
    TerminalTabPhase.Gone -> ", gone"
}

@Composable
private fun TerminalTabStrip(
    tabs: List<TerminalTab>,
    activeTabId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onRename: ((String) -> Unit)?,
    onAdd: (() -> Unit)?,
    canAdd: Boolean,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reducedMotion = rememberReducedMotion()
    val activeIndex = tabs.indexOfFirst { it.id == activeTabId }

    LaunchedEffect(activeIndex, tabs.size) {
        if (activeIndex < 0) return@LaunchedEffect
        scope.launch {
            if (reducedMotion) listState.scrollToItem(activeIndex) else listState.animateScrollToItem(activeIndex)
        }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LazyRow(
                    state = listState,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(tabs, key = { it.id }) { tab ->
                        TerminalTabChip(
                            tab = tab,
                            active = tab.id == activeTabId,
                            onSelect = { onSelect(tab.id) },
                            onClose = { onClose(tab.id) },
                            onRename = onRename?.let { rename -> { rename(tab.id) } },
                        )
                    }
                }
                if (onAdd != null) {
                    CompactIconAction(icon = Icons.Filled.Add, label = "New session", onClick = onAdd, enabled = canAdd)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun TerminalTabChip(
    tab: TerminalTab,
    active: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onRename: (() -> Unit)?,
) {
    val background = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val foreground = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
    val icon = iconFor(tab.phase)
    val accessibleLabel = "${tab.label}${suffixFor(tab.phase)}"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(TAB_HEIGHT)
            .clip(CircleShape)
            .background(background),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxHeight()
                .combinedClickable(onClick = onSelect, onLongClick = onRename)
                .padding(start = 14.dp, end = if (icon == null) 14.dp else 8.dp)
                .semantics(mergeDescendants = true) {
                    role = Role.Tab
                    selected = active
                    contentDescription = accessibleLabel
                    if (onRename != null) {
                        customActions = listOf(CustomAccessibilityAction("Rename") { onRename(); true })
                    }
                },
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(14.dp))
            }
            Text(
                tab.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = foreground,
                modifier = Modifier.widthIn(max = 140.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(TOUCH_TARGET)
                .combinedClickable(onClick = onClose)
                .semantics {
                    contentDescription = "Close ${tab.label}"
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, contentDescription = null, tint = foreground, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun TerminalBannerOverlay(banner: TerminalBanner, modifier: Modifier = Modifier) {
    val error = banner.tone == TerminalBannerTone.Error
    val ink = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        tonalElevation = 2.dp,
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .padding(8.dp)
            .semantics {
                liveRegion = if (error) LiveRegionMode.Assertive else LiveRegionMode.Polite
                if (error) this.error("")
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            when (banner.tone) {
                TerminalBannerTone.Busy -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                TerminalBannerTone.Error -> Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
                TerminalBannerTone.Info -> Icon(Icons.Filled.Info, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
            }
            Text(
                banner.message,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = ink,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (banner.actionLabel != null && banner.onAction != null) {
                TextButton(onClick = banner.onAction) {
                    Text(banner.actionLabel)
                }
            }
        }
    }
}

@Composable
private fun TerminalFailureCard(failure: TerminalFailure) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        if (failure.icon != null) {
            EmptyState(
                icon = failure.icon,
                title = failure.title,
                message = failure.message,
                action = if (failure.actionLabel != null && failure.onAction != null) {
                    failure.actionLabel to failure.onAction
                } else {
                    null
                },
            )
        } else {
            ErrorState(
                title = failure.title,
                message = failure.message ?: "",
                retryLabel = failure.actionLabel ?: "Retry",
                onRetry = failure.onAction,
            )
        }
    }
}

/**
 * True when the platform's "remove animations" accessibility setting is on.
 *
 * Android has no dedicated reduced-motion flag; turning that setting on sets
 * every animator duration scale to zero, which is also what Developer
 * Options' own scale sliders do. Either way, zero means the same thing here:
 * snap instead of animating.
 */
@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
