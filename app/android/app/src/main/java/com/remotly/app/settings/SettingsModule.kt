package com.remotly.app.settings

// Process-wide settings store instance. Set once from RemotlyCore
// before the bridge methods are reachable; null means the store failed to
// initialize and the bridge reports the storage error state instead of
// guessing.
object SettingsModule {
    @Volatile
    var store: SettingsStore? = null

    // Native transport callbacks can arrive while the JS runtime is paused.
    // Keep the user's notification choice in memory so those callbacks never
    // need to synchronously read preferences on a socket or main-thread path.
    @Volatile
    var notifyEnabled: Boolean = false
}
