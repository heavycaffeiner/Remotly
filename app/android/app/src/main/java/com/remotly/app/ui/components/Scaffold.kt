package com.remotly.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * One action in the app bar.
 *
 * [title] is both the visible menu label and the icon button's accessible
 * name, so an icon-only control can never ship without one.
 */
data class ScreenAction(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
)

/** Keep one primary action visible; secondary actions live in the overflow menu. */
private const val INLINE_ACTIONS = 1

/**
 * The shell every routed screen sits in.
 *
 * Actions past the third fold into an overflow menu rather than shrinking the
 * title, because a title that truncates to nothing tells the user less than a
 * menu they have to open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemotlyScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: List<ScreenAction> = emptyList(),
    snackbarHostState: SnackbarHostState? = null,
    bare: Boolean = false,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val inline = actions.take(INLINE_ACTIONS)
    val overflow = actions.drop(INLINE_ACTIONS)

    Scaffold(
        modifier = modifier,
        topBar = {
            if (bare) {
                if (actions.isNotEmpty()) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ScreenActions(inline, overflow, menuOpen) { menuOpen = it }
                        }
                    }
                }
            } else {
                TopAppBar(
                    title = {
                        BarTitle(title = title, subtitle = subtitle)
                    },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = { if (menuOpen) menuOpen = false else onBack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        ScreenActions(inline, overflow, menuOpen) { menuOpen = it }
                    },
                )
            }
        },
        bottomBar = bottomBar,
        snackbarHost = {
            if (snackbarHostState != null) SnackbarHost(snackbarHostState)
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) { content(padding) }
    }
}

/** The action buttons, with everything past the third behind one menu. */
@Composable
private fun ScreenActions(
    inline: List<ScreenAction>,
    overflow: List<ScreenAction>,
    menuOpen: Boolean,
    setMenuOpen: (Boolean) -> Unit,
) {
    for (action in inline) {
        IconButton(
            onClick = action.onClick,
            enabled = action.enabled,
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            Icon(action.icon, contentDescription = action.title)
        }
    }
    if (overflow.isEmpty()) return
    IconButton(onClick = { setMenuOpen(true) }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { setMenuOpen(false) }) {
        for (action in overflow) {
            DropdownMenuItem(
                text = { Text(action.title) },
                enabled = action.enabled,
                leadingIcon = { Icon(action.icon, contentDescription = null) },
                onClick = {
                    setMenuOpen(false)
                    action.onClick()
                },
            )
        }
    }
}

/** The app bar title, with an optional second line under it. */
@Composable
private fun BarTitle(title: String, subtitle: String?) {
    if (subtitle == null) {
        Text(title, maxLines = 1, modifier = Modifier.semantics { heading() })
        return
    }
    Column {
        Text(title, maxLines = 1, modifier = Modifier.semantics { heading() })
        Text(
            subtitle,
            maxLines = 1,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
