package com.remotly.app.bridge

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.ModuleSpec
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.remotly.app.camera.RemotlyCameraModule
import com.remotly.app.terminal.fabric.RemotlyTerminalViewManager
import com.remotly.app.specs.NativeRemotlyAppInfoSpec
import com.remotly.app.specs.NativeRemotlyCameraSpec
import com.remotly.app.specs.NativeRemotlyFileIOSpec
import com.remotly.app.specs.NativeRemotlyFilesSpec
import com.remotly.app.specs.NativeRemotlyHostsSpec
import com.remotly.app.specs.NativeRemotlyNotifySpec
import com.remotly.app.specs.NativeRemotlyPairingSpec
import com.remotly.app.specs.NativeRemotlySettingsSpec
import com.remotly.app.specs.NativeRemotlySftpSpec
import com.remotly.app.specs.NativeRemotlyTerminalStoreSpec
import com.remotly.app.specs.NativeRemotlySshHostSpec
import com.remotly.app.specs.NativeRemotlySshSpec
import com.remotly.app.specs.NativeRemotlyTransportSpec
import com.remotly.app.specs.NativeRemotlyWorkspaceSpec

// Registers the daemon-side TurboModules (transport, hosts, pairing, workspace,
// settings, notify) and the SSH/SFTP/file modules (RN-05) in one package.
class RemotlyTurboPackage : BaseReactPackage() {

    // The Fabric terminal component (RN-06) and the QR scanner component
    // (RN-08). Registered as view managers so the codegen'd views are created
    // through the Fabric tree.
    override fun getViewManagers(reactContext: ReactApplicationContext): List<ModuleSpec> =
        listOf(
            ModuleSpec.viewManagerSpec { RemotlyTerminalViewManager() },
        )

    override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
        when (name) {
            NativeRemotlyTransportSpec.NAME -> RemotlyTransportModule(reactContext)
            NativeRemotlyHostsSpec.NAME -> RemotlyHostsModule(reactContext)
            NativeRemotlyPairingSpec.NAME -> RemotlyPairingModule(reactContext)
            NativeRemotlyWorkspaceSpec.NAME -> RemotlyWorkspaceModule(reactContext)
            NativeRemotlySettingsSpec.NAME -> RemotlySettingsModule(reactContext)
            NativeRemotlyNotifySpec.NAME -> RemotlyNotifyModule(reactContext)
            NativeRemotlySshHostSpec.NAME -> RemotlySshHostModule(reactContext)
            NativeRemotlySshSpec.NAME -> RemotlySshModule(reactContext)
            NativeRemotlySftpSpec.NAME -> RemotlySftpModule(reactContext)
            NativeRemotlyTerminalStoreSpec.NAME -> RemotlyTerminalStoreModule(reactContext)
            NativeRemotlyFilesSpec.NAME -> RemotlyFilesModule(reactContext)
            NativeRemotlyFileIOSpec.NAME -> RemotlyFileIOModule(reactContext)
            NativeRemotlyCameraSpec.NAME -> RemotlyCameraModule(reactContext)
            NativeRemotlyAppInfoSpec.NAME -> RemotlyAppInfoModule(reactContext)
            else -> null
        }

    override fun getReactModuleInfoProvider(): ReactModuleInfoProvider =
        ReactModuleInfoProvider {
            mapOf(
                NativeRemotlyTransportSpec.NAME to
                    ReactModuleInfo(
                        NativeRemotlyTransportSpec.NAME,
                        NativeRemotlyTransportSpec.NAME,
                        false,
                        false,
                        false,
                        true,
                    ),
                NativeRemotlyHostsSpec.NAME to
                    ReactModuleInfo(
                        NativeRemotlyHostsSpec.NAME,
                        NativeRemotlyHostsSpec.NAME,
                        false,
                        false,
                        false,
                        true,
                    ),
                NativeRemotlyPairingSpec.NAME to
                    ReactModuleInfo(
                        NativeRemotlyPairingSpec.NAME,
                        NativeRemotlyPairingSpec.NAME,
                        false,
                        false,
                        false,
                        true,
                    ),
                NativeRemotlyWorkspaceSpec.NAME to
                    ReactModuleInfo(
                        NativeRemotlyWorkspaceSpec.NAME,
                        NativeRemotlyWorkspaceSpec.NAME,
                        false,
                        false,
                        false,
                        true,
                    ),
                NativeRemotlySettingsSpec.NAME to
                    ReactModuleInfo(
                        NativeRemotlySettingsSpec.NAME,
                        NativeRemotlySettingsSpec.NAME,
                        false,
                        false,
                        false,
                        true,
                    ),
                NativeRemotlyNotifySpec.NAME to
                    ReactModuleInfo(
                        NativeRemotlyNotifySpec.NAME,
                        NativeRemotlyNotifySpec.NAME,
                        false,
                        false,
                        false,
                        true,
                    ),
                NativeRemotlySshHostSpec.NAME to
                    ReactModuleInfo(
                        NativeRemotlySshHostSpec.NAME,
                        NativeRemotlySshHostSpec.NAME,
                        false,
                        false,
                        false,
                        true,
                    ),
                NativeRemotlySshSpec.NAME to
                    ReactModuleInfo(
                        NativeRemotlySshSpec.NAME,
                        NativeRemotlySshSpec.NAME,
                        false,
                        false,
                        false,
                        true,
                    ),
                NativeRemotlySftpSpec.NAME to
                    ReactModuleInfo(
                        NativeRemotlySftpSpec.NAME,
                        NativeRemotlySftpSpec.NAME,
                        false,
                        false,
                        false,
                        true,
                    ),
                NativeRemotlyTerminalStoreSpec.NAME to
                    ReactModuleInfo(
                        NativeRemotlyTerminalStoreSpec.NAME,
                        NativeRemotlyTerminalStoreSpec.NAME,
                        false,
                        false,
                        false,
                        true,
                    ),
                NativeRemotlyFilesSpec.NAME to
                    ReactModuleInfo(
                        NativeRemotlyFilesSpec.NAME,
                        NativeRemotlyFilesSpec.NAME,
                        false,
                        false,
                        false,
                        true,
                    ),
                NativeRemotlyFileIOSpec.NAME to
                    ReactModuleInfo(
                        NativeRemotlyFileIOSpec.NAME,
                        NativeRemotlyFileIOSpec.NAME,
                        false,
                        false,
                        false,
                        true,
                    ),
                NativeRemotlyCameraSpec.NAME to
                    ReactModuleInfo(
                        NativeRemotlyCameraSpec.NAME,
                        NativeRemotlyCameraSpec.NAME,
                        false,
                        false,
                        false,
                        true,
                    ),
                NativeRemotlyAppInfoSpec.NAME to
                    ReactModuleInfo(
                        NativeRemotlyAppInfoSpec.NAME,
                        NativeRemotlyAppInfoSpec.NAME,
                        false,
                        false,
                        false,
                        true,
                    ),
            )
        }
}
