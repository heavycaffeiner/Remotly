# Go sshcore engine (gomobile bindings). The .so is opaque to R8, but the
# generated Java classes call into it through native methods whose names must
# not be renamed, so keep the package whole.
-keep class sshcore.** { *; }

# ghostty-vt terminal (libremotly_terminal.so). The .so calls the native* entry
# points on RemotlyTerminal and the listener callbacks (onBell/onTitle/onInput/
# onPtyWrite) on the TerminalView by method name through JNI; R8 must not rename
# the binding class or these methods.
-keep class com.remotly.app.terminal.** { *; }

# BouncyCastle crypto primitives (Noise handshake, frame cipher). The app uses
# the low-level crypto package directly, not the JCE provider; keep it whole
# because the primitive classes are interdependent and loaded by name.
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.crypto.** { *; }

# Gson. The transport control codec names its fields with @SerializedName,
# which Gson reads reflectively; keep the annotation classes and the Gson
# runtime. The bridge objects build anonymous TypeToken subclasses that
# capture their element type via reflection on the class's generic
# superclass, so R8 must not rename or drop the type argument: keep every
# TypeToken subclass whole, and the app model classes they name.
-keep class com.google.gson.** { *; }
-keepattributes *Annotation*
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keep class com.remotly.app.hosts.** { *; }
-keep class com.remotly.app.transport.** { *; }
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

# MLKit (QR scanner). The Firebase component runtime resolves the barcode
# component and its dependencies (ExecutorSelector, MlKitContext, the
# vision interfaces) reflectively by class identity; keep the whole MLKit and
# Firebase component tree so the graph's class references stay valid.
-keep class com.google.mlkit.** { *; }
-keep class com.google.firebase.components.** { *; }
-dontwarn com.google.mlkit.**
