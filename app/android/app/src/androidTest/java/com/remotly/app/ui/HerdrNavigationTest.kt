package com.remotly.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remotly.app.herdr.HerdrClient
import com.remotly.app.herdr.HerdrEventTransport
import com.remotly.app.herdr.HerdrStore
import com.remotly.app.herdr.HerdrTransport
import com.remotly.app.ssh.HerdrBridge
import com.remotly.app.terminal.TerminalStore
import com.remotly.app.ui.screens.HostSidebar
import com.remotly.app.ui.screens.TerminalScaffold
import com.remotly.app.ui.terminal.TerminalHandle
import com.remotly.app.ui.terminal.TerminalPane
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HerdrNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun herdrReplacesShellNavigationAndKeepsTerminalThroughPanelChanges() {
        val client = HerdrClient(HerdrTransport { _, command ->
            HerdrBridge.Outcome(0, (if (command.contains("snapshot")) SNAPSHOT else "{\"sessions\":[]}")
                .toByteArray(), ByteArray(0))
        })
        val store = HerdrStore(client, object : HerdrEventTransport {
            override fun subscribe(hostId: String, command: String, onLine: (String) -> Unit,
                onClosed: (String, String) -> Unit) {
                onLine("{\"result\":{\"type\":\"subscription_started\"}}")
            }
            override fun release(hostId: String) = Unit
        }, MutableStateFlow(true))
        val width = mutableStateOf(1200f)
        val open = mutableStateOf(true)
        val companion = mutableStateOf(false)
        val handle = TerminalHandle()
        val id = "herdr-navigation-${System.nanoTime()}"
        lateinit var nav: NavHostController
        try {
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(0.5f, 1f)) {
                    MaterialTheme {
                        nav = rememberNavController()
                        Box(Modifier.width(width.value.dp).height(800.dp)) {
                            WorkspaceShell(nav) {
                                NavHost(nav, startDestination = Routes.HOSTS) {
                                    composable(Routes.HOSTS) { Text("Hosts content") }
                                    composable(Routes.HERDR_WORKSPACE, arguments = listOf(
                                        navArgument(Routes.ARG_HOST_ID) { type = NavType.StringType },
                                        navArgument(Routes.ARG_HOST_NAME) { defaultValue = "Test host" },
                                        navArgument(Routes.ARG_WORKSPACE_ID) { nullable = true; defaultValue = null; type = NavType.StringType },
                                        navArgument(Routes.ARG_LABEL) { nullable = true; defaultValue = null; type = NavType.StringType },
                                        navArgument(Routes.ARG_SESSION) { nullable = true; defaultValue = null; type = NavType.StringType },
                                    )) {
                                        HostSidebar(hostId = "test-host", hostName = "Test host", session = null,
                                            open = open.value, onClose = { open.value = false }, client = client,
                                            store = store, permanent = width.value >= 840f && !companion.value,
                                            currentWorkspaceId = "w1", onEnterWorkspace = {}, onOpenShells = {}, onOpenFiles = {}) {
                                            TerminalScaffold(title = "Herdr terminal", onBack = { open.value = false },
                                                onMenu = { open.value = !open.value }) { modifier ->
                                                TerminalPane(sessionKey = id, fontSizeSp = 14, cursorStyle = "block",
                                                    onInput = {}, onPtyWrite = {}, onResize = { _, _ -> }, onLinkCopied = {},
                                                    onFontSizeChanged = {}, handle = handle, modifier = modifier.fillMaxSize())
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            compose.onNodeWithText("Remotly").assertIsDisplayed()
            compose.runOnIdle { nav.navigate(Routes.herdrWorkspace("test-host", "Test host")) }
            compose.waitUntil { compose.onAllNodesWithContentDescription("Hide workspaces and tabs").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Remotly").assertDoesNotExist()
            compose.onNodeWithContentDescription("Resize navigation sidebar").assertDoesNotExist()
            compose.onNodeWithContentDescription("Show navigation").assertDoesNotExist()
            compose.onNodeWithContentDescription("Open workspace selector").assertDoesNotExist()
            compose.onAllNodesWithContentDescription("Workspaces and tabs").assertCountEquals(1)
            var native = 0L
            var view = handle.view
            compose.runOnIdle {
                native = TerminalStore.take(id)
                view = handle.view
                assertTrue(native != 0L)
            }
            compose.onNodeWithContentDescription("Hide workspaces and tabs").performClick()
            compose.onNodeWithContentDescription("Workspaces and tabs").performClick()
            compose.onNodeWithContentDescription("Hide workspaces and tabs").assertIsDisplayed()
            compose.runOnIdle { width.value = 360f }
            compose.onNodeWithContentDescription("Close workspaces and tabs").assertIsDisplayed()
            compose.onAllNodesWithContentDescription("Workspaces and tabs").assertCountEquals(1)
            compose.onNodeWithContentDescription("Close workspaces and tabs").performClick()
            compose.onNodeWithContentDescription("Workspaces and tabs").performClick()
            compose.onNodeWithContentDescription("Close workspaces and tabs").assertIsDisplayed()
            compose.runOnIdle { width.value = 1200f }
            compose.onNodeWithContentDescription("Hide workspaces and tabs").assertIsDisplayed()
            compose.runOnIdle { companion.value = true }
            compose.onNodeWithContentDescription("Close workspaces and tabs").assertIsDisplayed()
            compose.runOnIdle { companion.value = false }
            compose.onNodeWithContentDescription("Hide workspaces and tabs").assertIsDisplayed()
            compose.onNodeWithContentDescription("Hide workspaces and tabs").performClick()
            compose.onNodeWithContentDescription("Switch workspace, Herdr terminal").performClick()
            compose.onNodeWithContentDescription("Close workspace selector").assertIsDisplayed()
            compose.onNodeWithContentDescription("Close workspace selector").performClick()
            compose.runOnIdle {
                assertEquals(native, TerminalStore.take(id))
                assertSame(view, handle.view)
                nav.popBackStack()
            }
            compose.onNodeWithText("Remotly").assertIsDisplayed()
        } finally {
            store.close()
            compose.runOnIdle { handle.view?.release(); TerminalStore.release(id) }
        }
    }

    private companion object {
        const val SNAPSHOT = """{"result":{"snapshot":{"focused_workspace_id":"w1","focused_tab_id":"w1:t1","workspaces":[{"workspace_id":"w1","label":"Workspace one","number":1,"tab_count":1,"pane_count":1,"active_tab_id":"w1:t1","focused":true,"agent_status":"unknown"}],"tabs":[{"tab_id":"w1:t1","workspace_id":"w1","label":"Shell tab","number":1,"pane_count":1,"focused":true,"agent_status":"unknown"}],"panes":[]}}}"""
    }
}
