package com.remotly.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

const val EXPANDED_LAYOUT_MIN_WIDTH_DP = 840

val WideHostListWidth = 480.dp
val WideSidebarWidth = 288.dp
val ScreenHorizontalPadding = 12.dp

data class WorkspaceLayout(val widthDp: Int, val heightDp: Int) {
    val compactHeight: Boolean get() = heightDp < 480
    val rail: Boolean get() = widthDp >= 600 || compactHeight
    val sidebar: Boolean get() = widthDp >= 840
    val companion: Boolean get() = widthDp >= 1200
}

val LocalWorkspaceLayout = staticCompositionLocalOf { WorkspaceLayout(360, 800) }
val LocalWorkspaceActions = staticCompositionLocalOf { WorkspaceActions() }

data class WorkspaceActions(
    val selectWork: () -> Unit = {},
    val toggleSidebar: () -> Unit = {},
    val sidebarVisible: Boolean = false,
    val modalOpen: Boolean = false,
    val openTransfers: () -> Unit = {},
    val companionRequested: Boolean = false,
    val toggleCompanion: () -> Unit = {},
)

@Composable
fun hardwareKeyboardAttached(): Boolean =
    LocalConfiguration.current.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS

fun companionFits(workWidthDp: Float, workHeightDp: Float): Boolean =
    workWidthDp >= 960f && workHeightDp >= 320f
