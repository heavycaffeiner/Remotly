package com.remotly.app.workspace

// Process-wide workspace store instance. Set once from RemotlyCore
// before the bridge methods are reachable; null means the store failed to
// initialize and the bridge reports the storage error state instead of
// guessing.
object WorkspaceModule {
    @Volatile
    var store: WorkspaceStore? = null
}
