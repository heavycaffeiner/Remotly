package com.remotly.app.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotly.app.settings.AppSettings
import com.remotly.app.settings.SettingsState
import com.remotly.app.ui.components.RemotlyScreen
import java.net.URLDecoder

private const val SAVE_FAILED = "That setting could not be saved."

/** Offered repeat delays: short enough to feel instant, long enough to tap. */
private val REPEAT_DELAY_CHOICES = listOf(200, 400, 700)

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
fun SettingsContent() {
    val settings by SettingsState.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var notice by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsSectionHeader("Appearance")

            SettingsGroup {
                Text("Theme", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    THEME_OPTIONS.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = settings.themeMode == value,
                            onClick = { apply { it.copy(themeMode = value) } },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = THEME_OPTIONS.size),
                            label = { Text(label) },
                        )
                    }
                }
            }

            SettingsSwitchRow(
                title = "Dynamic color",
                description = if (dynamicColorSupported) {
                    "Follow the system color scheme where the device supports it."
                } else {
                    "Requires Android 12 or later. This device cannot use it."
                },
                checked = settings.dynamicColor,
                enabled = dynamicColorSupported,
                onCheckedChange = { value -> apply { it.copy(dynamicColor = value) } },
            )

            SettingsSectionHeader("Terminal")

            SettingsGroup {
                Text("Font size", style = MaterialTheme.typography.labelLarge)
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
                        modifier = Modifier
                            .width(64.dp)
                            .semantics {
                                contentDescription = "Terminal font size, ${settings.terminalFontSize} sp"
                            },
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
                Text("Cursor", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    CURSOR_OPTIONS.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = settings.cursorStyle == value,
                            onClick = { apply { it.copy(cursorStyle = value) } },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = CURSOR_OPTIONS.size),
                            label = { Text(label) },
                        )
                    }
                }
            }

            SettingsSwitchRow(
                title = "Open the keyboard automatically",
                description = "Show the keyboard when you open a terminal from a host.",
                checked = settings.openKeyboardOnTerminal,
                onCheckedChange = { value -> apply { it.copy(openKeyboardOnTerminal = value) } },
            )

            SettingsSwitchRow(
                title = "Show the extra key row",
                description = "Esc, Tab, Ctrl, Alt, arrows, and symbols above the keyboard.",
                checked = settings.showExtraKeyRow,
                onCheckedChange = { value -> apply { it.copy(showExtraKeyRow = value) } },
            )

            SettingsGroup {
                Text("Hold an extra key to repeat after", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    REPEAT_DELAY_CHOICES.forEachIndexed { index, ms ->
                        SegmentedButton(
                            selected = settings.keyRepeatDelayMs == ms,
                            onClick = { apply { it.copy(keyRepeatDelayMs = ms) } },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = REPEAT_DELAY_CHOICES.size),
                            label = { Text("$ms ms") },
                        )
                    }
                }
            }

            SettingsSwitchRow(
                title = "Vibrate on key presses",
                description = "A short buzz for each extra key, and for a terminal bell.",
                checked = settings.hapticFeedback,
                onCheckedChange = { value -> apply { it.copy(hapticFeedback = value) } },
            )

            SettingsSectionHeader("Files")

            SettingRow(
                title = "Download folder",
                description = if (settings.downloadFolderUri.isEmpty()) {
                    "Not chosen yet. You will be asked on the first download."
                } else {
                    folderLabel(settings.downloadFolderUri)
                },
                trailing = {
                    OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Change") }
                },
            )

            SettingsSectionHeader("About")

            SettingRow(title = "App version", description = versionLabel)

            SettingRow(
                title = "Android system settings",
                description = "Permissions for this app.",
                onClick = {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        },
                    )
                },
            )

            SettingRow(
                title = "Reset settings",
                description = "Restores the defaults on this screen. Hosts, SSH credentials, and " +
                    "accepted host keys are kept.",
                destructive = true,
                onClick = { confirmReset = true },
            )

            Text(
                "Settings are stored on this device only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
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

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/**
 * A Switch's thumb position alone tells a screen reader nothing about what
 * it controls, so the accessible name carries the setting's title and its
 * current state together.
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
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
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics {
                contentDescription = "$title, ${if (checked) "on" else "off"}"
            },
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
        .padding(horizontal = 16.dp, vertical = 8.dp)
    Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = titleColor)
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
