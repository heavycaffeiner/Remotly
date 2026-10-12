package com.remotly.app.ui.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.hasText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remotly.app.session.FilesTabs
import com.remotly.app.settings.AppSettings
import com.remotly.app.settings.SettingsModule
import com.remotly.app.settings.SettingsState
import com.remotly.app.settings.SettingsStore
import com.remotly.app.ssh.PlaintextCredentialCipher
import com.remotly.app.ssh.SftpBridge
import com.remotly.app.ssh.SftpConnectResult
import com.remotly.app.ssh.SftpEntry
import com.remotly.app.ssh.SshCredential
import com.remotly.app.ssh.SshHost
import com.remotly.app.ssh.SshHostStore
import com.remotly.app.ssh.SshModule
import com.remotly.app.ssh.engine.SftpConnection
import com.remotly.app.ssh.engine.SftpConnectionFactory
import com.remotly.app.ssh.engine.SftpOps
import com.remotly.app.ui.screens.FilesNavigation
import com.remotly.app.ui.screens.FilesScreen
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilesScreenStateTest {
    @get:Rule val compose = createComposeRule()
    private val previousSettingsStore = SettingsModule.store
    private val previousHostStore = SshModule.store
    private val previousFactory = SshModule.sftpConnectionFactory
    private val directory = Files.createTempDirectory("files-screen-test").toFile()
    private val store = SettingsStore(directory.resolve("settings.json"))
    private lateinit var hostId: String
    private val tabId = "files-relocation-test"
    private val navigation = FilesNavigation()

    @Before
    fun setUp() {
        store.save(AppSettings())
        SettingsModule.store = store
        runBlocking { SettingsState.load() }
        val hosts = SshHostStore(directory.resolve("hosts.json"), PlaintextCredentialCipher())
        hostId = hosts.add("Test", "files-test.invalid", 22, "test", SshCredential.Password("test".toByteArray())).id
        SshModule.store = hosts
        SshModule.sftpConnectionFactory = SftpConnectionFactory { BrowserConnection() }
    }

    @After
    fun tearDown() {
        SftpBridge.close(hostId)
        FilesTabs.forget(tabId)
        SettingsModule.store = previousSettingsStore
        runBlocking { SettingsState.load() }
        SshModule.store = previousHostStore
        SshModule.sftpConnectionFactory = previousFactory
        directory.deleteRecursively()
    }

    @Test
    fun externalStateSurvivesRemovalAndCompanionRemountWithOrderedBack() {
        val state = FilesBrowserState("/home", listOf("/"))
        val placement = mutableIntStateOf(0)
        compose.setContent {
            MaterialTheme {
                if (placement.intValue != 1) {
                    key(placement.intValue) {
                        Box(if (placement.intValue == 2) Modifier.widthIn(max = 320.dp) else Modifier) {
                            FilesScreen(hostId, {}, tabId, navigation, browserState = state)
                        }
                    }
                }
            }
        }
        awaitReady()
        awaitEntry("src")
        compose.onNodeWithText("src").performClick()
        compose.waitUntil(10_000) { state.directory.value == "/home/src" }
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithText("Search").performTextInput("archive")
        awaitEntry("archive.txt")
        compose.onNodeWithText("archive.txt").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("/", "/home"), state.visited.value)
            placement.intValue = 1
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Close search").assertDoesNotExist()
        compose.runOnIdle { assertFalse(navigation.goBack()) }
        compose.runOnIdle { placement.intValue = 2 }
        awaitReady()
        awaitEntry("archive.txt")
        compose.onNodeWithContentDescription("Close search").assertIsDisplayed()
        compose.onNodeWithText("archive").assertIsDisplayed()
        compose.onNodeWithText("archive.txt").assertIsDisplayed()
        compose.onNodeWithContentDescription("Actions for .secret").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("/home/src", state.directory.value)
            assertEquals(listOf("/", "/home"), state.visited.value)
            assertEquals("archive", state.searchQuery.value)
            assertTrue(state.searching.value)
            assertTrue(state.hasDirectory)
            assertTrue(navigation.goBack())
            assertFalse(state.searching.value)
            assertEquals("", state.searchQuery.value)
            assertEquals("/home/src", state.directory.value)
            assertEquals(listOf("/", "/home"), state.visited.value)
        }
        compose.onNodeWithContentDescription("Search").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(navigation.goBack())
            assertEquals("/home", state.directory.value)
            assertEquals(listOf("/"), state.visited.value)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(navigation.goBack())
            assertEquals("/", state.directory.value)
            assertTrue(state.visited.value.isEmpty())
            assertFalse(navigation.goBack())
        }
        compose.onNodeWithContentDescription("Search").assertIsDisplayed()
    }

    @Test
    fun saveableScreenStateRestoresDirectoryHistoryAndSearchWithOrderedBack() {
        FilesTabs.setCwd(tabId, "/home")
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme { FilesScreen(hostId, {}, tabId, navigation) }
        }
        awaitReady()
        awaitEntry("src")
        compose.onNodeWithText("src").performClick()
        compose.waitUntil(10_000) { FilesTabs.remembered(tabId) == "/home/src" }
        awaitEntry("archive.txt")
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithText("Search").performTextInput("archive")
        restoration.emulateSavedInstanceStateRestore()
        awaitReady()
        awaitEntry("archive.txt")
        compose.onNodeWithContentDescription("Close search").assertIsDisplayed()
        compose.onNodeWithText("archive").assertIsDisplayed()
        compose.onNodeWithText("src").assertIsDisplayed()
        compose.runOnIdle { assertTrue(navigation.goBack()) }
        compose.onNodeWithContentDescription("Search").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("/home/src", FilesTabs.remembered(tabId))
            assertTrue(navigation.goBack())
            assertEquals("/home", FilesTabs.remembered(tabId))
            assertFalse(navigation.goBack())
        }
    }

    @Test
    fun failedSortSaveRestoresSelectionAndShowsSnackbar() = verifyFailedPreference("Size")

    @Test
    fun failedDirectionSaveRestoresSelectionAndShowsSnackbar() = verifyFailedPreference("Descending")

    @Test
    fun failedHiddenFilesSaveRestoresSelectionAndShowsSnackbar() = verifyFailedPreference("Show hidden files")

    @Test
    fun toolbarAndSortControlsRemainReachableWith48DpTargetsAtDoubleFontScale() {
        mount(fontScale = 2f)
        awaitReady()
        for (label in listOf("Search", "Sort", "Add", "Refresh")) {
            compose.onNodeWithContentDescription(label).assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        compose.onNodeWithContentDescription("Search").performClick()
        compose.onNodeWithText("Search").assertIsDisplayed().performTextInput("archive")
        compose.onNodeWithContentDescription("Close search").performClick()
        compose.onNodeWithContentDescription("Sort").performClick()
        for (label in listOf("Name", "Size", "Modified", "Type", "Ascending", "Descending", "Show hidden files", "Done")) {
            val node = if (label == "Done") compose.onNodeWithText(label) else sortOption(label)
            if (label != "Done") node.performScrollTo()
            node.assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Add").performClick()
        compose.onNodeWithText("New folder").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Upload file").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    private fun verifyFailedPreference(label: String) {
        val durable = store.load()
        mount()
        awaitReady()
        compose.onNodeWithContentDescription("Sort").performClick()
        // A missing store rejects writes without touching the previously saved file.
        SettingsModule.store = null
        sortOption(label).performScrollTo().performClick()
        compose.waitUntil(10_000) {
            SettingsState.error.value != null && SettingsState.settings.value == durable
        }
        sortOption("Name").performScrollTo().assertIsSelected()
        sortOption("Ascending").performScrollTo().assertIsSelected()
        sortOption("Show hidden files").performScrollTo().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off"),
        )
        compose.onNodeWithText("Done").performClick()
        awaitEntry("Could not save file preferences.")
        compose.onNodeWithText("Could not save file preferences.").assertIsDisplayed()
        assertEquals(durable, store.load())
        compose.onNodeWithContentDescription("Actions for .secret").assertDoesNotExist()
    }

    private fun sortOption(label: String) = compose.onNode(hasText(label) and
        SemanticsMatcher.expectValue(SemanticsProperties.Role,
            if (label == "Show hidden files") Role.Checkbox else Role.RadioButton))

    private fun mount(fontScale: Float = 1f) {
        val state = FilesBrowserState("/home")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    FilesScreen(hostId, {}, tabId, navigation, browserState = state)
                }
            }
        }
    }

    private fun awaitReady() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Sort")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitEntry(text: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private class BrowserConnection : SftpConnection {
        override fun connect(host: SshHost, credential: SshCredential) = SftpConnectResult.Ready
        override fun decideHostKey(accept: Boolean) = Unit
        override fun close() = Unit
        override val sftp = object : SftpOps {
            override fun list(path: String) = buildList {
                if (path == "/home") add(SftpEntry("src", true, false, 0, 0, 0))
                add(SftpEntry("archive.txt", false, false, 1024, 0, 0))
                add(SftpEntry(".secret", false, false, 1, 0, 0))
            }
            override fun realPath(path: String) = "/home"
            override fun stat(path: String): SftpEntry = error("Unexpected stat")
            override fun mkdir(path: String): Unit = error("Unexpected mkdir")
            override fun rename(oldPath: String, newPath: String): Unit = error("Unexpected rename")
            override fun removeFile(path: String): Unit = error("Unexpected remove")
            override fun removeDir(path: String): Unit = error("Unexpected remove")
            override fun download(path: String, chunkSize: Int, onChunk: (Long, ByteArray) -> Unit): Long = error("Unexpected download")
            override fun downloadFrom(path: String, startOffset: Long, chunkSize: Int, onChunk: (Long, ByteArray) -> Unit): Long = error("Unexpected download")
            override fun uploadAppend(path: String, rewind: Long, chunkSize: Int, onChunk: (Long) -> ByteArray?): Long = error("Unexpected upload")
            override fun upload(path: String, chunkSize: Int, truncate: Boolean, exclusive: Boolean, onChunk: (Long) -> ByteArray?): Long = error("Unexpected upload")
            override fun close() = Unit
        }
    }
}
