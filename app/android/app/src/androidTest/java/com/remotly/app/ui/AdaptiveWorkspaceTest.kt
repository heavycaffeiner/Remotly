package com.remotly.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remotly.app.session.SshSessions
import com.remotly.app.session.SshTransport
import com.remotly.app.settings.AppSettings
import com.remotly.app.settings.SettingsModule
import com.remotly.app.settings.SettingsState
import com.remotly.app.settings.SettingsStore
import com.remotly.app.ssh.*
import com.remotly.app.ui.screens.SshTerminalScreen
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveWorkspaceTest {
    @get:Rule val compose = createComposeRule()
    private val directory = Files.createTempDirectory("adaptive-ui").toFile()
    private val oldHosts = SshModule.store
    private val oldSettings = SettingsModule.store
    private val oldTransport = SshSessions.transport
    private lateinit var hostId: String
    private val transport = RecordingTransport()

    @Before fun prepare() {
        val hosts = SshHostStore(directory.resolve("hosts.json"), PlaintextCredentialCipher())
        hostId = hosts.add("Test host", "adaptive.invalid", 22, "user", SshCredential.Password("test".toByteArray())).id
        SshModule.store = hosts
        SettingsModule.store = SettingsStore(directory.resolve("settings.json")).also { it.save(AppSettings(openKeyboardOnTerminal = false)) }
        runBlocking { SettingsState.load() }
        SshSessions.transport = transport
    }
    @After fun restore() {
        SshSessions.closeHost(hostId)
        SshSessions.transport = oldTransport
        SshModule.store = oldHosts
        SettingsModule.store = oldSettings
        runBlocking { SettingsState.load() }
        directory.deleteRecursively()
    }

    @Test fun liveResizeKeepsShellAndDoesNotResizeBackgroundSessions() {
        SshSessions.openTab(hostId)
        val second = SshSessions.openTab(hostId)!!
        val width = mutableStateOf(412f)
        val height = mutableStateOf(915f)
        compose.setContent {
            val density = LocalDensity.current
            val fit = minOf(density.density, 1f)
            CompositionLocalProvider(LocalDensity provides Density(fit, 1f)) {
                MaterialTheme {
                    CompositionLocalProvider(LocalWorkspaceLayout provides WorkspaceLayout(width.value.toInt(), height.value.toInt())) {
                        Box(Modifier.width(width.value.dp).height(height.value.dp)) { SshTerminalScreen(hostId, rememberNavController()) }
                    }
                }
            }
        }
        compose.waitUntil { transport.resizes.isNotEmpty() }
        val connections = transport.connected.size
        compose.runOnIdle { transport.resizes.clear() }
        for ((w,h) in listOf(360f to 800f, 740f to 360f, 840f to 600f, 1200f to 800f, 1600f to 900f)) {
            compose.runOnIdle { width.value = w; height.value = h }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Switch workspace, Test host").assertIsDisplayed()
        }
        compose.runOnIdle {
            Assert.assertEquals(connections, transport.connected.size)
            Assert.assertEquals(second, SshSessions.state(hostId).value.tabs.activeSessionId)
            Assert.assertTrue(transport.resizes.all { it == second })
        }
    }

    @Test fun terminalMenuAndTransferBadgeDoNotResizeOrCoverTheViewport() {
        val session = SshSessions.openTab(hostId)!!
        com.remotly.app.transfers.TransferRegistry.reset()
        compose.setContent {
            MaterialTheme {
                SshTerminalScreen(hostId, rememberNavController())
            }
        }
        compose.waitUntil { transport.resizes.isNotEmpty() }
        compose.waitForIdle()
        val before = transport.resizes.size
        compose.onNodeWithContentDescription("Terminal actions").performClick()
        compose.onNodeWithText("Show keyboard").assertExists()
        compose.runOnIdle { Assert.assertEquals(before, transport.resizes.size) }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle {
            com.remotly.app.transfers.TransferRegistry.register(
                "layout-transfer", com.remotly.app.transfers.TransferDirection.Upload,
                "/test", "test", hostId, 100, false, {},
            )
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Show transfers").assertIsDisplayed()
        compose.runOnIdle {
            Assert.assertEquals(before, transport.resizes.size)
            Assert.assertEquals(session, SshSessions.state(hostId).value.tabs.activeSessionId)
            com.remotly.app.transfers.TransferRegistry.reset()
        }
    }

    @Test fun companionResizePreservesNativeTerminalAndFocusedSurface() {
        val size = mutableStateOf(1200f to 800f)
        val requested = mutableStateOf(false)
        val filesFocused = mutableStateOf(false)
        val handle = com.remotly.app.ui.terminal.TerminalHandle()
        val id = "companion-${System.nanoTime()}"
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.5f, 1f)) {
                MaterialTheme {
                    com.remotly.app.ui.screens.FilesCompanion(
                        filesSelected = filesFocused.value,
                        requested = requested.value,
                        onFilesFocus = { filesFocused.value = true },
                        modifier = Modifier.width(size.value.first.dp).height(size.value.second.dp),
                        terminal = { modifier ->
                            com.remotly.app.ui.terminal.TerminalPane(
                                sessionKey = id, fontSizeSp = 14, cursorStyle = "block",
                                onInput = {}, onPtyWrite = {}, onResize = { _, _ -> }, onLinkCopied = {},
                                onFontSizeChanged = {}, modifier = modifier, handle = handle,
                            )
                        },
                        files = { modifier -> Text("Files companion", modifier = modifier) },
                    )
                }
            }
        }
        compose.waitForIdle()
        var native = 0L
        compose.runOnIdle {
            native = com.remotly.app.terminal.TerminalStore.take(id)
            Assert.assertTrue(native != 0L)
            requested.value = true
        }
        compose.waitForIdle()
        compose.onNodeWithText("Files companion").assertIsDisplayed()
        compose.runOnIdle { filesFocused.value = true; size.value = 360f to 800f }
        compose.waitForIdle()
        compose.onNodeWithText("Files companion").assertIsDisplayed()
        compose.runOnIdle { filesFocused.value = false }
        compose.waitForIdle()
        compose.onNodeWithText("Files companion").assertDoesNotExist()
        compose.runOnIdle { size.value = 1600f to 900f }
        compose.waitForIdle()
        compose.onNodeWithText("Files companion").assertIsDisplayed()
        compose.runOnIdle {
            Assert.assertEquals(native, com.remotly.app.terminal.TerminalStore.take(id))
            Assert.assertNotNull(handle.view)
            handle.view?.feed("history preserved".toByteArray())
        }
    }

    @Test fun workspaceShellUsesWindowBoundsAndKeepsCompactRootsReachable() {
        val size = mutableStateOf(360f to 800f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.5f, 1f)) {
                MaterialTheme {
                    Box(Modifier.width(size.value.first.dp).height(size.value.second.dp)) {
                        WorkspaceShell(rememberNavController()) { Text("Working content") }
                    }
                }
            }
        }
        for ((w,h) in listOf(360f to 800f, 740f to 360f, 600f to 900f, 840f to 600f, 1200f to 800f, 1600f to 900f)) {
            compose.runOnIdle { size.value = w to h }
            compose.waitForIdle()
            compose.onAllNodesWithText("Hosts").onFirst().assertIsDisplayed()
            compose.onAllNodesWithText("Sessions").onFirst().assertIsDisplayed()
            compose.onAllNodesWithText("Settings").onFirst().assertIsDisplayed()
            compose.onNodeWithText("Working content").assertIsDisplayed()
        }
    }

    @Test fun hidingNavigationRemovesTheRailAndCanBeUndone() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.5f, 1f)) {
                MaterialTheme {
                    Box(Modifier.width(1200.dp).height(800.dp)) {
                        WorkspaceShell(rememberNavController()) {
                            com.remotly.app.ui.components.RemotlyScreen("Test screen") { padding ->
                                Text("Working content", Modifier.padding(padding))
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Hosts").assertIsDisplayed()
        val resize = compose.onNodeWithContentDescription("Resize navigation sidebar")
        resize.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        resize.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionRight) }
        resize.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "312 dp"))
        compose.onAllNodesWithContentDescription("Hide navigation").onFirst().performClick()
        compose.onNodeWithText("Hosts").assertDoesNotExist()
        compose.onNodeWithText("Sessions").assertDoesNotExist()
        compose.onNodeWithText("Settings").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show navigation").performClick()
        compose.onNodeWithText("Hosts").assertIsDisplayed()
    }

    @Test fun filesPaneHasCloseAndDockControlsWithoutUnmountingTerminal() {
        val open = mutableStateOf(true)
        var closed = 0
        var docked = 0
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.5f, 1f)) {
                MaterialTheme {
                    com.remotly.app.ui.screens.FilesCompanion(
                        filesSelected = false, requested = open.value, onFilesFocus = {},
                        modifier = Modifier.width(1200.dp).height(800.dp),
                        terminal = { Text("Terminal content", it) }, files = { Text("File list", it) },
                        onClose = { closed++; open.value = false }, onDock = { docked++ },
                    )
                }
            }
        }
        val resize = compose.onNodeWithContentDescription("Resize terminal and Files panes")
        resize.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        resize.performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionLeft) }
        resize.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Terminal 59 percent"))
        compose.onNodeWithContentDescription("Use Files as a tab").performClick()
        compose.runOnIdle { Assert.assertEquals(1, docked) }
        compose.onNodeWithContentDescription("Close Files pane").performClick()
        compose.runOnIdle { Assert.assertEquals(1, closed) }
        compose.onNodeWithText("File list").assertDoesNotExist()
        compose.onNodeWithText("Terminal content").assertIsDisplayed()
    }

    private class RecordingTransport : SshTransport {
        val connected = mutableListOf<String>()
        val resizes = mutableListOf<String>()
        val sinks = mutableMapOf<String, (String, Map<String,Any?>)->Unit>()
        override fun connect(hostId: String, sessionId: String, cols: Int, rows: Int) {
            connected += sessionId
            sinks[hostId]?.invoke("state", mapOf("sessionId" to sessionId, "state" to "active"))
        }
        override fun write(hostId: String, sessionId: String, data: ByteArray) = Unit
        override fun resize(hostId: String, sessionId: String, cols: Int, rows: Int) { resizes += sessionId }
        override fun hostKey(hostId: String, sessionId: String, decision: String) = Unit
        override fun close(hostId: String, sessionId: String) = Unit
        override fun closeHost(hostId: String) = Unit
        override fun setEventSink(hostId: String, sink: ((String, Map<String, Any?>)->Unit)?) {
            if (sink == null) sinks.remove(hostId) else sinks[hostId] = sink
        }
    }
}
