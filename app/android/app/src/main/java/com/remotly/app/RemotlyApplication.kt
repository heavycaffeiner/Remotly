package com.remotly.app

import android.app.Application
import com.remotly.app.core.RemotlyCore

/**
 * Brings up the process-wide stores before any screen can ask for them.
 *
 * [RemotlyCore.init] is idempotent and runs on the main thread here, so the
 * host store, the settings store, and the SSH engine factories exist by the
 * time the first activity is created.
 */
class RemotlyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RemotlyCore.init(this)
    }
}
