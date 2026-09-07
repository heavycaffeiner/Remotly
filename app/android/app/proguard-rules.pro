# Go sshcore engine (gomobile bindings). The .so is opaque to R8, but the
# generated Java classes call into it through native methods whose names must
# not be renamed, so keep the package whole.
-keep class sshcore.** { *; }

# ghostty-vt terminal (libremotly_terminal.so). The .so calls the native* entry
# points on RemotlyTerminal and the listener callbacks (onBell/onTitle/onInput/
# onPtyWrite) on the TerminalView by method name through JNI; R8 must not rename
# the binding class or these methods.
-keep class com.remotly.app.terminal.** { *; }

# Gson. SshHostStore, SettingsStore, and the bridge modules use Gson's
# JsonObject/JsonParser API and field-name reflection to serialize records;
# keep the runtime and its annotations.
-keep class com.google.gson.** { *; }
-keepattributes *Annotation*
# The SSH host store serializes SshHost and KnownHostKey by field name (no
# @SerializedName), so their field names must survive obfuscation for the
# persisted store data to round-trip.
-keep class com.remotly.app.ssh.SshHost { *; }
-keep class com.remotly.app.ssh.KnownHostKey { *; }
# SFTP entries and the host-key challenge info are serialized by field name and
# handed to JS as JSON.
-keep class com.remotly.app.ssh.SftpEntry { *; }
-keep class com.remotly.app.ssh.HostKeyInfo { *; }
# The bridge serializes small view objects (HostView, KeyView, ...) to JSON by
# field name for JS. R8 renames their fields, so keep the bridge package whole.
-keep class com.remotly.app.bridge.** { *; }
