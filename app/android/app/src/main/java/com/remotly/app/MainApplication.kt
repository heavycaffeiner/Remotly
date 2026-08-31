package com.remotly.app

import android.app.Application
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.remotly.app.bridge.RemotlyCore
import com.remotly.app.bridge.RemotlyTurboPackage

class MainApplication : Application(), ReactApplication {

  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      packageList =
        PackageList(this).packages.apply {
          add(RemotlyTurboPackage())
        },
    )
  }

  override fun onCreate() {
    super.onCreate()
    // Core stores and the transport hub initialize here, on the main thread,
    // before any TurboModule is constructed. Idempotent.
    RemotlyCore.init(this)
    loadReactNative(this)
  }
}
