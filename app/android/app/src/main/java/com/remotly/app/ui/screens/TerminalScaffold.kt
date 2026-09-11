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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remotly.app.ui.components.ScreenAction
import com.remotly.app.ui.terminal.ModifierKey
import com.remotly.app.ui.terminal.REPEAT_DELAY_MS
import com.remotly.app.ui.terminal.TerminalKeyRow
import kotlinx.coroutines.launch

/** Minimum height of the compact top bar; text can grow it for large font scales. */
private val BAR_MIN_HEIGHT = 56.dp

/** Touch target every icon-only control meets, however tall its visual row is. */
private val TOUCH_TARGET = 48.dp

/** Height of one pill in the tab strip. Tall enough on its own to need no touch-target trick. */
private val TAB_HEIGHT = 36.dp

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
 * Groups related terminal actions under a visible heading in the overflow
 * menu. Keys not named by any group remain in an "Other" section.
 */
data class TerminalActionGroup(
    val title: String,
    val actionKeys: Set<String>,
)

/**
 * Replaces the terminal body entirely: there is no session to show, or
 * nothing has connected yet.
 *
 * Failure details are kept separate from the short human-readable message so
 * a caller can offer the complete raw reason without forcing it into a
 * two-line banner.
 */
data class TerminalFailure(
    val icon: ImageVector? = null,
    val title: String,
    val message: String? = null,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val secondaryActionLabel: String? = null,
    val onSecondaryAction: (() -> Unit)? = null,
    val details: String? = null,
)

/**
 * The chrome shared by every terminal screen.
 *
 * [content] receives a [Modifier] already sized to fill the clipped terminal
 * body; the caller chains its own gesture modifiers onto it before handing it
 * to its `TerminalPane`.
 */
@Composable
fun TerminalScaffold(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    onMenu: (() -> Unit)? = null,
    actions: List<ScreenAction> = emptyList(),
    actionGroups: List<TerminalActionGroup> = emptyList(),
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
            actionGroups = actionGroups,
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
    actionGroups: List<TerminalActionGroup>,
    onActionsMenuOpen: () -> Unit,
    showKeyboardAction: Boolean,
    onKeyboard: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = BAR_MIN_HEIGHT)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                if (onMenu != null) {
                    CompactIconAction(icon = Icons.Filled.Menu, label = "Workspaces and tabs", onClick = onMenu)
                }
                CompactIconAction(icon = Icons.AutoMirrored.Filled.ArrowBack, label = "Back", onClick = onBack)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    subtitle
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
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
                        val sections = terminalActionSections(actions, actionGroups)
                        for ((sectionIndex, section) in sections.withIndex()) {
                            if (sectionIndex > 0) HorizontalDivider()
                            Text(
                                section.title,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                            for (action in section.actions) {
                                DropdownMenuItem(
                                    text = { Text(action.title) },
                                    enabled = action.enabled,
                                    leadingIcon = {
                                        Icon(
                                            action.icon,
                                            contentDescription = null,
                                            tint = if (action.destructive) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                defaultActionTint()
                                            },
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
            }
        }
    }
}

private data class TerminalActionSection(
    val title: String,
    val actions: List<ScreenAction>,
)

/**
 * Applies declared task groups while keeping every destructive action in its
 * own final section, even when a caller accidentally places it in another
 * group.
 */
private fun terminalActionSections(
    actions: List<ScreenAction>,
    declaredGroups: List<TerminalActionGroup>,
): List<TerminalActionSection> {
    if (actions.isEmpty()) return emptyList()
    val remaining = actions.toMutableList()
    val sections = mutableListOf<TerminalActionSection>()

    fun addSection(title: String, candidates: List<ScreenAction>) {
        val ordinary = candidates.filterNot { it.destructive }
        val destructive = candidates.filter { it.destructive }
        if (ordinary.isNotEmpty()) sections += TerminalActionSection(title, ordinary)
        if (destructive.isNotEmpty()) sections += TerminalActionSection("Destructive actions", destructive)
    }

    if (declaredGroups.isEmpty()) {
        addSection("Terminal actions", actions)
        return sections
    }

    for (group in declaredGroups) {
        val matching = remaining.filter { it.key in group.actionKeys }
        if (matching.isEmpty()) continue
        remaining.removeAll(matching.toSet())
        addSection(group.title, matching)
    }
    if (remaining.isNotEmpty()) addSection("Other actions", remaining)
    return sections
}

/** The overflow menu item's default tint, matching ordinary (non-destructive) menu text. */
@Composable
private fun defaultActionTint() = MaterialTheme.colorScheme.onSurface

/**
 * An icon-only control with a real touch target of at least [TOUCH_TARGET].
 * The surrounding bar grows with its text, so this control never relies on
 * clipping or a visually oversized hit area.
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

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
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
    val background = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val foreground = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    val icon = iconFor(tab.phase)
    val accessibleLabel = "${tab.label}${suffixFor(tab.phase)}"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(TAB_HEIGHT)
            .clip(MaterialTheme.shapes.small)
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
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
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
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
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
    var detailsExpanded by remember(failure.details) { mutableStateOf(false) }
    val details = failure.details?.takeIf { it.isNotBlank() }
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .semantics {
                liveRegion = LiveRegionMode.Assertive
                error(failure.message ?: failure.title)
            },
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    failure.icon ?: Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    failure.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                if (!failure.message.isNullOrBlank()) {
                    Text(
                        failure.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (failure.actionLabel != null && failure.onAction != null) {
                    Button(
                        onClick = failure.onAction,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(failure.actionLabel)
                    }
                }
                if (failure.secondaryActionLabel != null && failure.onSecondaryAction != null) {
                    FilledTonalButton(
                        onClick = failure.onSecondaryAction,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(failure.secondaryActionLabel)
                    }
                }
                if (details != null) {
                    TextButton(
                        onClick = { detailsExpanded = !detailsExpanded },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (detailsExpanded) "Hide connection details" else "Show connection details")
                    }
                    if (detailsExpanded) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                details,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
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
