package com.remotly.app.settings

// Process-wide settings store instance. Set once from RemotlyCore
// before the bridge methods are reachable; null means the store failed to
// initialize and the bridge reports the storage error state instead of
// guessing.
object SettingsModule {
    @Volatile
    var store: SettingsStore? = null
}
