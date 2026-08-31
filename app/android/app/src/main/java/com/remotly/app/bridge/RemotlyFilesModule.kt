package com.remotly.app.bridge

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.remotly.app.files.FilesOpenParams
import com.remotly.app.specs.NativeRemotlyFilesSpec

// The files-screen one-shot hand-off (remotly.files.*). The opening page stores
// the host to open before navigating; the files page drains it on mount.
class RemotlyFilesModule(reactContext: ReactApplicationContext) :
    NativeRemotlyFilesSpec(reactContext) {

    override fun storeOpen(open: String, promise: Promise) {
        if (open.length < 1 || open.length > 512) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "open out of range")
            return
        }
        FilesOpenParams.store(open)
        promise.resolve(null)
    }

    override fun takeOpen(promise: Promise) {
        promise.resolve(Arguments.makeNativeMap(mapOf("open" to (FilesOpenParams.take() ?: ""))))
    }
}
