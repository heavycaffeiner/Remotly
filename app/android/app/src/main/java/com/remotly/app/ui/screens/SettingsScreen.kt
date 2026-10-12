package com.remotly.app.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.remotly.app.settings.ExtraKeyVisibility
import com.remotly.app.settings.TerminalThemeDraftState
import com.remotly.app.terminal.TerminalThemes
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotly.app.platform.openAppSettings
import com.remotly.app.settings.AppSettings
import com.remotly.app.settings.SettingsState
import com.remotly.app.ui.ScreenHorizontalPadding
import com.remotly.app.ui.components.ChoiceRow
import com.remotly.app.ui.components.RemotlyScreen
import java.net.URLDecoder

private const val SAVE_FAILED = "That setting could not be saved."

/** Offered repeat delays: short enough to feel instant, long enough to tap. */
private val REPEAT_DELAY_CHOICES = listOf(200, 400, 700)

private val REPEAT_DELAY_OPTIONS = REPEAT_DELAY_CHOICES.map { it to "$it ms" }

private val THEME_OPTIONS = listOf(
    AppSettings.THEME_SYSTEM to "System",
    AppSettings.THEME_LIGHT to "Light",
    AppSettings.THEME_DARK to "Dark",
)

private val CURSOR_OPTIONS = listOf(
    AppSettings.CURSOR_BLOCK to "Block",
    AppSettings.CURSOR_BAR to "Bar",
    AppSettings.CURSOR_UNDERLINE to "Underline",
)

/**
 * The Settings tab: appearance, terminal, file, and about preferences,
 * persisted through [SettingsState].
 *
 * Every control writes immediately. [SettingsState] rolls a failed write
 * back to what is actually on disk, so a control that stayed in its new
 * position would claim a preference the next launch will not have; the
 * snackbar here only reports why it moved back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(initialSection: String = "Application appearance") {
    val settings by SettingsState.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var notice by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmRecovery by remember { mutableStateOf(false) }
    var editTheme by remember { mutableStateOf(false) }
    var section by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialSection) }
    val recoveryError by SettingsState.error.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun apply(change: (AppSettings) -> AppSettings) {
        SettingsState.update(onFailure = { notice = SAVE_FAILED }, change = change)
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val granted = try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            true
        } catch (e: SecurityException) {
            false
        }
        // The picker result crosses a process boundary: a tree this app was
        // not actually granted access to must never be stored, or a later
        // download would silently fail against a folder that looks configured.
        if (granted) {
            apply { it.copy(downloadFolderUri = uri.toString()) }
        } else {
            notice = "Could not get permission to use that folder."
        }
    }

    val versionLabel = remember(context) { appVersionLabel(context.packageManager, context.packageName) }

    LaunchedEffect(notice) {
        if (notice.isNotBlank()) {
            snackbarHostState.showSnackbar(notice)
            notice = ""
        }
    }

    RemotlyScreen(title = "Settings", snackbarHostState = snackbarHostState) { padding ->
        SettingsSectionLayout(section, { section = it }, Modifier.fillMaxSize().padding(padding)) {
            recoveryError?.let { error ->
                SettingsGroup {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { scope.launch { SettingsState.load() } }) { Text("Retry") }
                    TextButton(onClick = { confirmRecovery = true }) { Text("Back up and reset settings") }
                }
            }
            if (section == "Application appearance") {
            SettingsSectionHeader("Application appearance")

            SettingsGroup {
                Text("Theme", style = MaterialTheme.typography.bodyLarge)
                SettingsSingleChoice(
                    options = THEME_OPTIONS,
                    selected = settings.themeMode,
                    onSelected = { value -> apply { it.copy(themeMode = value) } },
                )
            }

            SettingsSwitchRow(
                title = "Dynamic color",
                description = if (dynamicColorSupported) null else "Requires Android 12",
                checked = settings.dynamicColor,
                enabled = dynamicColorSupported,
                onCheckedChange = { value -> apply { it.copy(dynamicColor = value) } },
            )

            }
            if (section == "Terminal appearance") {
            SettingsSectionHeader("Terminal appearance")

            SettingsGroup {
                TerminalThemePreview(settings.terminalTheme.resolve())
                TerminalThemeChoices(
                    selected = settings.terminalTheme.id,
                    custom = settings.terminalTheme.custom,
                    onSelected = { id ->
                        if (id == "custom") {
                            TerminalThemeDraftState.begin(settings.terminalTheme.custom)
                            editTheme = true
                        } else apply { it.copy(terminalTheme = it.terminalTheme.copy(id = id)) }
                    },
                )
                TextButton(onClick = {
                    TerminalThemeDraftState.begin(settings.terminalTheme.resolve())
                    editTheme = true
                }) { Text("Edit custom theme") }
            }

            SettingsGroup {
                Text("Font size", style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            val next = (settings.terminalFontSize - 1).coerceAtLeast(AppSettings.MIN_FONT_SIZE)
                            if (next != settings.terminalFontSize) apply { it.copy(terminalFontSize = next) }
                        },
                        enabled = settings.terminalFontSize > AppSettings.MIN_FONT_SIZE,
                    ) {
                        Icon(Icons.Filled.Remove, contentDescription = "Decrease terminal font size")
                    }
                    Text(
                        "${settings.terminalFontSize} sp",
                        modifier = Modifier.padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    IconButton(
                        onClick = {
                            val next = (settings.terminalFontSize + 1).coerceAtMost(AppSettings.MAX_FONT_SIZE)
                            if (next != settings.terminalFontSize) apply { it.copy(terminalFontSize = next) }
                        },
                        enabled = settings.terminalFontSize < AppSettings.MAX_FONT_SIZE,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Increase terminal font size")
                    }
                }
            }

            SettingsGroup {
                Text("Cursor", style = MaterialTheme.typography.bodyLarge)
                SettingsSingleChoice(
                    options = CURSOR_OPTIONS,
                    selected = settings.cursorStyle,
                    onSelected = { value -> apply { it.copy(cursorStyle = value) } },
                )
            }

            }
            if (section == "Input") {
            SettingsSectionHeader("Input")

            SettingsSwitchRow(
                title = "Open keyboard automatically",
                checked = settings.openKeyboardOnTerminal,
                onCheckedChange = { value -> apply { it.copy(openKeyboardOnTerminal = value) } },
            )

            SettingsGroup {
                Text("Extra keys", style = MaterialTheme.typography.bodyLarge)
                SettingsSingleChoice(
                    options = ExtraKeyVisibility.entries.map { it to it.name },
                    selected = settings.extraKeyVisibility,
                    onSelected = { value -> apply { it.copy(extraKeyVisibility = value, showExtraKeyRow = value != ExtraKeyVisibility.Hidden) } },
                )
            }

            SettingsSwitchRow(
                title = "Navigation gestures",
                checked = settings.navigationGesturesEnabled,
                onCheckedChange = { value -> apply { it.copy(navigationGesturesEnabled = value) } },
            )

            SettingsGroup {
                Text("Hold an extra key to repeat after", style = MaterialTheme.typography.bodyLarge)
                SettingsSingleChoice(
                    options = REPEAT_DELAY_OPTIONS,
                    selected = settings.keyRepeatDelayMs,
                    onSelected = { value -> apply { it.copy(keyRepeatDelayMs = value) } },
                )
            }

            SettingsSwitchRow(
                title = "Vibrate on key presses",
                checked = settings.hapticFeedback,
                onCheckedChange = { value -> apply { it.copy(hapticFeedback = value) } },
            )

            }
            if (section == "Files") {
            SettingsSectionHeader("Files")

            SettingRow(
                title = "Download folder",
                description = if (settings.downloadFolderUri.isEmpty()) {
                    null
                } else {
                    folderLabel(settings.downloadFolderUri)
                },
                trailing = {
                    FilledTonalButton(onClick = { folderPicker.launch(null) }) { Text("Choose folder") }
                },
            )

            }
            if (section == "About") {
            SettingsSectionHeader("About")

            SettingRow(title = "App version", description = versionLabel)

            SettingRow(
                title = "Android system settings",
                onClick = { openAppSettings(context) },
            )

            SettingRow(
                title = "Reset settings",
                description = "Restores the defaults on this screen. Hosts, SSH credentials, and " +
                    "accepted host keys are kept.",
                destructive = true,
                onClick = { confirmReset = true },
            )
            }
        }
    }

    if (editTheme) TerminalThemeEditor(onClose = { editTheme = false })

    if (confirmRecovery) {
        AlertDialog(
            onDismissRequest = { confirmRecovery = false },
            title = { Text("Back up and reset settings?") },
            text = { Text("The current settings file will be copied to a recovery backup before defaults are saved. Hosts and credentials are kept.") },
            confirmButton = { TextButton(onClick = {
                confirmRecovery = false
                scope.launch { SettingsState.recoverWithDefaults() }
            }) { Text("Back up and reset") } },
            dismissButton = { TextButton(onClick = { confirmRecovery = false }) { Text("Cancel") } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset settings?") },
            text = {
                Text(
                    "Appearance, terminal, and file preferences return to their defaults. Your " +
                        "saved hosts, SSH credentials, and accepted host keys are not affected.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    apply { AppSettings() }
                }) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            },
        )
    }
}

private val SettingsSections = listOf(
    "Application appearance" to Icons.Default.Palette,
    "Terminal appearance" to Icons.Default.Terminal,
    "Input" to Icons.Default.Keyboard,
    "Files" to Icons.Default.Folder,
    "About" to Icons.Default.Info,
)

@Composable
private fun SettingsSectionLayout(
    section: String,
    onSelected: (String) -> Unit,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= 720.dp && LocalDensity.current.fontScale < 1.5f
        Column(Modifier.fillMaxSize()) {
            if (!wide) SettingsSectionPicker(section, onSelected)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (wide) {
                    Column(Modifier.width(224.dp).fillMaxHeight()
                        .verticalScroll(rememberScrollState()).padding(8.dp)) {
                        SettingsSections.forEach { (name, icon) ->
                            NavigationDrawerItem(label = { Text(name) }, selected = section == name,
                                onClick = { onSelected(name) }, icon = { Icon(icon, null) })
                        }
                    }
                    VerticalDivider(Modifier.fillMaxHeight())
                }
                key(section) {
                    Column(Modifier.weight(1f).widthIn(max = 840.dp).fillMaxHeight()
                        .verticalScroll(rememberScrollState()), content = content)
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionPicker(section: String, onSelected: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(section, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
            Icon(Icons.Default.KeyboardArrowDown, "Choose settings section")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SettingsSections.forEach { (name, icon) ->
                DropdownMenuItem(text = { Text(name) }, leadingIcon = { Icon(icon, null) },
                    onClick = { open = false; onSelected(name) })
            }
        }
    }
}

@Composable
private fun TerminalThemeChoices(
    selected: String,
    custom: com.remotly.app.terminal.TerminalTheme,
    onSelected: (String) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
        val columns = if (LocalDensity.current.fontScale >= 1.5f) 1 else when {
            maxWidth >= 560.dp -> 3
            maxWidth >= 340.dp -> 2
            else -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalThemes.labels.toList().chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (id, label) ->
                        val theme = TerminalThemes.presets[id] ?: custom
                        Surface(color = if (selected == id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.weight(1f).selectable(selected = selected == id,
                                role = Role.RadioButton, onClick = { onSelected(id) })) {
                            Column(Modifier.padding(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = selected == id, onClick = null)
                                    Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                }
                                Column(Modifier.fillMaxWidth().background(Color(theme.background)).padding(8.dp).clearAndSetSemantics {}) {
                                    Text("user@host", color = Color(theme.foreground), style = MaterialTheme.typography.bodySmall)
                                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                        theme.ansi.take(8).forEach { color ->
                                            Box(Modifier.weight(1f).height(12.dp).background(Color(color)))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = ScreenHorizontalPadding, end = ScreenHorizontalPadding, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenHorizontalPadding, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/**
 * Three options fit in one row of chips at the default text size. At
 * accessibility text sizes the labels would wrap, so a radio list takes over.
 */
@Composable
private fun <T> SettingsSingleChoice(
    options: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = options.size > 3 || LocalDensity.current.fontScale >= 1.3f || maxWidth < 300.dp
        if (stacked) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
            ) {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = selected == value,
                                onClick = { onSelected(value) },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == value, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else {
            ChoiceRow(options = options, selected = selected, onSelected = onSelected)
        }
    }
}

/**
 * The row owns the toggle semantics so its title and description are read
 * together with the native switch role and checked state.
 */
@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = ScreenHorizontalPadding, vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    description: String? = null,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val titleColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val rowModifier = Modifier
        .fillMaxWidth()
        .let { base ->
            if (onClick != null) {
                base.clickable(onClickLabel = title, role = Role.Button, onClick = onClick)
            } else {
                base
            }
        }
        .heightIn(min = 48.dp)
        .padding(horizontal = ScreenHorizontalPadding, vertical = 6.dp)
    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = titleColor, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/**
 * A readable name for a tree URI.
 *
 * The document id is the only human-facing part of a content URI, and it is
 * percent-encoded. A malformed URI still shows the raw value rather than
 * nothing.
 */
private fun folderLabel(treeUri: String): String {
    return try {
        val tail = treeUri.substringAfterLast('/')
        val decoded = URLDecoder.decode(tail, "UTF-8")
        val colon = decoded.lastIndexOf(':')
        val path = if (colon >= 0) decoded.substring(colon + 1) else decoded
        path.ifEmpty { "Device storage" }
    } catch (e: Exception) {
        treeUri
    }
}

private fun appVersionLabel(pm: PackageManager, packageName: String): String {
    return try {
        val info = pm.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    } catch (e: PackageManager.NameNotFoundException) {
        "Unavailable"
    }
}
