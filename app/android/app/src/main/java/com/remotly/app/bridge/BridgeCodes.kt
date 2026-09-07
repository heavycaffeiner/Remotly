package com.remotly.app.bridge

// Error codes the native modules reject with: 0 a generic bridge failure, -3
// an invalid parameter. They reach JS as the rejection code on the promise.
internal object BridgeCodes {
    const val FAIL = 0
    const val INVALID_PARAM = -3
}
