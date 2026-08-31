package com.remotly.app.hosts

// Process-wide host store instance. Set once from RemotlyCore before
// the bridge methods are reachable; null means the store failed to initialize
// and the bridge reports the storage error state instead of guessing.
object HostsModule {
    @Volatile
    var store: HostStore? = null
}
