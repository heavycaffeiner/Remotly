package com.remotly.app.ui.screens

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.remotly.app.ssh.HostKeyInfo
import com.remotly.app.ssh.HostKeyVerdict
import com.remotly.app.ssh.HostKeyVerifier
import com.remotly.app.ssh.KnownHostKey
import com.remotly.app.ssh.SftpConnectResult
import com.remotly.app.ssh.SshCredential
import com.remotly.app.ssh.SshHost
import com.remotly.app.ssh.SshHostStore
import com.remotly.app.ssh.SshModule
import com.remotly.app.ssh.engine.SftpConnection
import com.remotly.app.ui.ScreenHorizontalPadding
import com.remotly.app.ui.components.ChoiceRow
import com.remotly.app.ui.components.ErrorState
import com.remotly.app.ui.components.LoadingState
import com.remotly.app.ui.components.NoticeBar
import com.remotly.app.ui.components.NoticeTone
import com.remotly.app.ui.components.RemotlyScreen
import com.remotly.app.ui.components.RemotlyTextField
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Segment values for the authentication method picker.
private enum class AuthMethod(val label: String) {
    Key("Private key"),
    Password("Password"),
}

// Outcome of a one-off connection probe. Nothing here is ever persisted:
// running a test never touches the store.
private data class TestOutcome(
    val running: Boolean = false,
    val ok: Boolean? = null,
    val message: String = "",
    val algorithm: String = "",
    val fingerprint: String = "",
    val changed: Boolean = false,
)

// A private key above this size is not a key; the picker can be pointed at any
// file, and the bytes are held in memory until the form is submitted.
private const val MAX_KEY_BYTES = 1 * 1024 * 1024

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditorScreen(hostId: String?, onDone: () -> Unit) {
    val editing = !hostId.isNullOrEmpty()
    // Set once by the app shell before any screen is reachable; read once here.
    val store = remember { SshModule.store }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (store == null) {
        RemotlyScreenShell(editing = editing, onBack = onDone) { padding ->
            ErrorState(
                title = "Storage unavailable",
                message = "Host storage failed to start, so hosts cannot be opened or saved.",
                modifier = Modifier.padding(padding),
            )
        }
        return
    }

    var loading by remember { mutableStateOf(editing) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var existing by remember { mutableStateOf<SshHost?>(null) }

    var displayName by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    var username by rememberSaveable { mutableStateOf("") }
    var auth by rememberSaveable { mutableStateOf(AuthMethod.Key) }
    var keyFileName by rememberSaveable { mutableStateOf("") }
    // Secrets stay out of rememberSaveable: they must not land in a saved-state
    // bundle. A process restart loses them, which matches "never read back".
    var keyBytes by remember { mutableStateOf<ByteArray?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    // In edit mode the credential is only touched once the user asks for it.
    var replaceCredential by rememberSaveable { mutableStateOf(!editing) }

    var busy by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf("") }
    var saveAttempted by rememberSaveable { mutableStateOf(false) }
    var fileError by remember { mutableStateOf("") }
    var test by remember { mutableStateOf(TestOutcome()) }
    var confirmEndpoint by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(hostId) {
        if (!editing) return@LaunchedEffect
        val id = requireNotNull(hostId)
        val result = withContext(Dispatchers.IO) { runCatching { store.get(id) } }
        result.onSuccess { found ->
            if (found == null) {
                loadError = "That host is no longer saved."
            } else {
                existing = found
                displayName = found.displayName
                host = found.host
                port = found.port.toString()
                username = found.username
                auth = if (found.authKind == SshHost.AUTH_KEY) AuthMethod.Key else AuthMethod.Password
            }
        }.onFailure { e ->
            loadError = e.message ?: "The host could not be loaded."
        }
        loading = false
    }

    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { readKeyFile(context, uri) }
            outcome.onSuccess { (name, bytes) ->
                keyBytes = bytes
                keyFileName = name
                fileError = ""
            }.onFailure {
                fileError = "That file could not be read."
            }
        }
    }

    val portNum = port.toIntOrNull()
    val portValid = portNum != null && portNum in 1..65535
    val hostValid = host.trim().isNotEmpty()
    val userValid = username.trim().isNotEmpty()
    val secretValid = if (auth == AuthMethod.Password) password.isNotEmpty() else keyBytes != null
    val formIssues = buildList {
        if (!hostValid) add(if (host.trim().isEmpty()) "host (required)" else "host")
        if (!portValid) add(if (port.trim().isEmpty()) "port (required)" else "port")
        if (!userValid) add(if (username.trim().isEmpty()) "username (required)" else "username")
        if (replaceCredential && !secretValid) {
            add(if (auth == AuthMethod.Key) "private key (required)" else "password (required)")
        }
    }
    val formValid = formIssues.isEmpty()
    val formSummary = if (formIssues.isEmpty()) {
        ""
    } else {
        "Complete or correct: ${formIssues.joinToString()}."
    }
    val endpointChanged = existing != null &&
        (host.trim() != existing?.host || portNum != existing?.port)
    val fallbackName = if (displayName.trim().isEmpty() && username.trim().isNotEmpty() && host.trim().isNotEmpty()) {
        "${username.trim()}@${host.trim()}"
    } else {
        ""
    }
    val canTest = !test.running &&
        hostValid &&
        userValid &&
        portValid &&
        (if (replaceCredential) secretValid else editing && existing != null)
    val dirty = existing?.let { saved ->
        displayName != saved.displayName ||
            host != saved.host ||
            port != saved.port.toString() ||
            username != saved.username ||
            auth != (if (saved.authKind == SshHost.AUTH_KEY) AuthMethod.Key else AuthMethod.Password) ||
            replaceCredential
    } ?: listOf(displayName, host, username, password, passphrase, keyFileName).any { it.isNotEmpty() } ||
        port != "22" || auth != AuthMethod.Key

    fun requestClose() {
        if (busy) return
        if (dirty) confirmDiscard = true else onDone()
    }

    BackHandler(onBack = ::requestClose)

    fun buildCredential(): SshCredential = if (auth == AuthMethod.Key) {
        SshCredential.Key(
            privateKey = keyBytes?.copyOf() ?: ByteArray(0),
            passphrase = passphrase.ifEmpty { null }?.toByteArray(Charsets.UTF_8),
        )
    } else {
        SshCredential.Password(password.toByteArray(Charsets.UTF_8))
    }

    fun runTest() {
        if (!canTest) return
        test = TestOutcome(running = true)
        val probeHost = host.trim()
        val probeUser = username.trim()
        val probePort = requireNotNull(portNum)
        val useReplacement = replaceCredential
        val replacementCredential = if (useReplacement) buildCredential() else null
        val knownKeys = existing?.knownKeys ?: emptyList()
        val storedHostId = hostId
        scope.launch {
            test = withContext(Dispatchers.IO) {
                try {
                    val credential = replacementCredential
                        ?: store.credential(requireNotNull(storedHostId))
                    try {
                        testConnection(probeHost, probePort, probeUser, credential, knownKeys)
                    } finally {
                        clearCredential(credential)
                    }
                } catch (e: Exception) {
                    TestOutcome(
                        ok = false,
                        message = e.message ?: "The connection did not succeed.",
                    )
                }
            }
        }
    }

    fun persist() {
        busy = true
        formError = ""
        val trimmedName = displayName.trim()
        val trimmedHost = host.trim()
        val trimmedUser = username.trim()
        val portToSave = requireNotNull(portNum)
        val credential = if (replaceCredential) buildCredential() else null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (editing) {
                        store.update(
                            requireNotNull(hostId),
                            SshHostStore.HostPatch(
                                displayName = trimmedName,
                                host = trimmedHost,
                                port = portToSave,
                                username = trimmedUser,
                                credential = credential,
                            ),
                        )
                    } else {
                        store.add(
                            displayName = trimmedName,
                            host = trimmedHost,
                            port = portToSave,
                            username = trimmedUser,
                            credential = requireNotNull(credential),
                        )
                    }
                }
            }
            // The id can change with the endpoint on an update; the caller only
            // needs to know the save happened, not which id it landed on.
            result.onSuccess {
                password = ""
                passphrase = ""
                keyBytes = null
                busy = false
                onDone()
            }.onFailure { e ->
                formError = e.message ?: "The host could not be saved."
                busy = false
            }
        }
    }

    fun save() {
        if (busy) return
        saveAttempted = true
        formError = ""
        if (!formValid) return
        // Changing the endpoint drops the accepted host keys, so it is
        // confirmed rather than done silently.
        if (endpointChanged) {
            confirmEndpoint = true
            return
        }
        persist()
    }

    if (loading) {
        RemotlyScreenShell(editing = editing, onBack = ::requestClose) { padding ->
            LoadingState(label = "Loading host", modifier = Modifier.padding(padding))
        }
        return
    }

    RemotlyScreen(
        title = if (editing) "Edit SSH host" else "Add SSH host",
        subtitle = existing?.let { sshHostDisplayName(it) },
        onBack = ::requestClose,
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ScreenHorizontalPadding, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        onClick = ::requestClose,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Cancel")
                    }
                    Button(
                        onClick = { save() },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(16.dp)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                                strokeWidth = 2.dp,
                                color = LocalContentColor.current,
                            )
                        } else {
                            Icon(Icons.Filled.Save, contentDescription = null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("Save")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenHorizontalPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (saveAttempted && !formValid) {
                NoticeBar(message = formSummary, tone = NoticeTone.Danger)
            }
            if (formError.isNotEmpty()) {
                NoticeBar(message = formError, tone = NoticeTone.Danger)
            }

            FormSection(
                title = "Identity",
                description = "Label is optional.",
            ) {
                RemotlyTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Label (optional)") },
                    supportingText = if (fallbackName.isNotEmpty()) {
                        { Text("Shown as $fallbackName") }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            FormSection(
                title = "Connection",
                description = "Host, port, and username are required.",
            ) {
                val hostError = saveAttempted && !hostValid
                RemotlyTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host (required)") },
                    placeholder = { Text("server.example.com") },
                    // A hostname is not prose. Left to the keyboard's
                    // suggestions it gets capitalized and autocorrected into
                    // a different address.
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                    ),
                    isError = hostError,
                    supportingText = if (hostError) {
                        {
                            AnnouncedError(
                                if (host.trim().isEmpty()) {
                                    "Enter a hostname or address."
                                } else {
                                    "Enter a valid hostname or address."
                                },
                            )
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val portError = saveAttempted && !portValid
                RemotlyTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port (required)") },
                    placeholder = { Text("22") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = portError,
                    supportingText = if (portError) {
                        {
                            AnnouncedError(
                                if (port.trim().isEmpty()) {
                                    "Enter a port from 1 to 65535."
                                } else {
                                    "The port must be between 1 and 65535."
                                },
                            )
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val userError = saveAttempted && !userValid
                RemotlyTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username (required)") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    isError = userError,
                    supportingText = if (userError) {
                        { AnnouncedError("Enter a username.") }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (endpointChanged) {
                    NoticeBar(
                        message = "Changing the address or port establishes trust again: " +
                            "the accepted host key is not carried over.",
                        tone = NoticeTone.Danger,
                    )
                }
            }

            FormSection(
                title = "Authentication",
                description = if (editing && !replaceCredential) {
                    "Using the saved credential. Replace it only if needed."
                } else {
                    "Choose a method and provide the required credential."
                },
            ) {
                if (editing && !replaceCredential) {
                    val savedKind = if (existing?.authKind == SshHost.AUTH_KEY) {
                        "A private key is saved for this host."
                    } else {
                        "A password is saved for this host."
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                savedKind,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { replaceCredential = true }) {
                                Text("Replace")
                            }
                        }
                    }
                } else {
                    ChoiceRow(
                        options = AuthMethod.entries.map { it to it.label },
                        selected = auth,
                        onSelected = { auth = it },
                    )
                    if (auth == AuthMethod.Key) {
                        FilledTonalButton(onClick = { keyPicker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (keyFileName.isNotEmpty()) "Imported $keyFileName" else "Import a key file")
                        }
                        if (fileError.isNotEmpty()) {
                            NoticeBar(message = fileError, tone = NoticeTone.Danger)
                        }
                        if (saveAttempted && keyBytes == null) {
                            AnnouncedError("Import a private key.")
                        }
                        RemotlyTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = { Text("Passphrase (optional)") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                capitalization = KeyboardCapitalization.None,
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        val passwordError = saveAttempted && password.isEmpty()
                        RemotlyTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password (required)") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                capitalization = KeyboardCapitalization.None,
                            ),
                            isError = passwordError,
                            supportingText = if (passwordError) {
                                { AnnouncedError("Enter a password.") }
                            } else {
                                null
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            FormSection(
                title = "Verification",
                description = if (editing && !replaceCredential) {
                    "Uses the saved credential to check the address. Nothing is saved."
                } else {
                    "Connects once to check the address and credential. Nothing is saved."
                },
            ) {
                FilledTonalButton(
                    onClick = { runTest() },
                    enabled = canTest,
                ) {
                    Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Test connection")
                }
                if (test.running) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(18.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            strokeWidth = 2.dp,
                        )
                        Text("Connecting", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (!test.running && test.ok != null) {
                    NoticeBar(
                        message = test.message,
                        tone = if (test.ok == true) NoticeTone.Info else NoticeTone.Danger,
                    )
                }
                if (!test.running && test.fingerprint.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (test.changed) {
                                "The server presented a different key than the one accepted before."
                            } else {
                                "Host key presented by the server"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (test.changed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            "${test.algorithm} ${test.fingerprint}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Passing the test does not trust this key. You confirm it the first " +
                                "time you connect.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            val knownKeys = existing?.knownKeys.orEmpty()
            if (editing && knownKeys.isNotEmpty()) {
                FormSection(title = "Accepted host keys") {
                    for (key in knownKeys) {
                        Text(
                            "${key.algorithm} ${key.fingerprint}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your unsaved host changes will be lost.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onDone()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
    if (confirmEndpoint) {
        AlertDialog(
            onDismissRequest = { confirmEndpoint = false },
            title = { Text("Change the address?") },
            text = {
                Text(
                    "This host is identified by its address and port. Changing them " +
                        "establishes trust again: the accepted host key is not carried " +
                        "over, and you confirm the new one the next time you connect.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmEndpoint = false
                        persist()
                    },
                    enabled = !busy,
                ) { Text("Change") }
            },
            dismissButton = {
                TextButton(onClick = { confirmEndpoint = false }) { Text("Cancel") }
            },
        )
    }
}

// The loading and error branches only ever need a bare title, before the
// record (and its display-name subtitle) is available.
@Composable
private fun RemotlyScreenShell(
    editing: Boolean,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    RemotlyScreen(
        title = if (editing) "Edit SSH host" else "Add SSH host",
        onBack = onBack,
        content = content,
    )
}

@Composable
private fun FormSection(
    title: String,
    description: String? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}

@Composable
private fun AnnouncedError(message: String) {
    Text(
        message,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

private fun sshHostDisplayName(host: SshHost): String =
    host.displayName.ifBlank { "${host.username}@${host.host}" }

// Probe credentials are short-lived copies. Clear their bytes as soon as the
// connection attempt has closed without touching the form's in-memory input.
private fun clearCredential(credential: SshCredential) {
    when (credential) {
        is SshCredential.Password -> credential.value.fill(0)
        is SshCredential.Key -> {
            credential.privateKey.fill(0)
            credential.passphrase?.fill(0)
        }
    }
}

// Reads a private key through the Storage Access Framework. The picker can
// point at anything, so the read is bounded and the name comes from the
// resolver rather than being trusted from the URI itself.
private fun readKeyFile(context: Context, uri: Uri): Result<Pair<String, ByteArray>> = runCatching {
    val resolver = context.contentResolver
    val name = queryDisplayName(resolver, uri).ifEmpty { "key" }
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > MAX_KEY_BYTES) throw IOException("file too large to be a private key")
            buffer.write(chunk, 0, read)
        }
        buffer.toByteArray()
    } ?: throw IOException("cannot open the selected file")
    name to bytes
}

private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx) ?: ""
    }
    return ""
}

// Connects once with no persistence: the host key is decided true for the
// duration of the probe regardless of the verdict, purely so the handshake
// can finish and report what the server presented. Nothing here writes to the
// store; a changed-key warning is derived from the caller's already-loaded
// known keys, not from re-reading them.
private fun testConnection(
    hostAddr: String,
    port: Int,
    user: String,
    credential: SshCredential,
    knownKeys: List<KnownHostKey>,
): TestOutcome {
    val factory = SshModule.sftpConnectionFactory
        ?: return TestOutcome(ok = false, message = "The SSH engine is unavailable.")

    var presented: HostKeyInfo? = null
    var changed = false
    lateinit var connection: SftpConnection
    connection = factory.create { info ->
        presented = info
        changed = HostKeyVerifier.verify(knownKeys, info) is HostKeyVerdict.Changed
        connection.decideHostKey(true)
    }

    // A probe never touches the store, so this is a throwaway record carrying
    // only what the engine reads for a connect: host, port, and username.
    val probeHost = SshHost(
        id = "",
        displayName = "",
        host = hostAddr,
        port = port,
        username = user,
        authKind = if (credential is SshCredential.Key) SshHost.AUTH_KEY else SshHost.AUTH_PASSWORD,
        credentialRef = "",
        knownKeys = emptyList(),
        createdAt = 0,
        updatedAt = 0,
    )

    return try {
        when (val result = connection.connect(probeHost, credential)) {
            is SftpConnectResult.Ready -> TestOutcome(
                ok = true,
                message = "The server accepted the credential.",
                algorithm = presented?.algorithm ?: "",
                fingerprint = presented?.fingerprint ?: "",
                changed = changed,
            )
            is SftpConnectResult.Failure -> TestOutcome(
                ok = false,
                message = result.message.ifEmpty { "The connection did not succeed." },
                algorithm = presented?.algorithm ?: "",
                fingerprint = presented?.fingerprint ?: "",
                changed = changed,
            )
        }
    } catch (e: Exception) {
        TestOutcome(ok = false, message = e.message ?: "The connection did not succeed.")
    } finally {
        try {
            connection.close()
        } catch (_: Exception) {
        }
    }
}
