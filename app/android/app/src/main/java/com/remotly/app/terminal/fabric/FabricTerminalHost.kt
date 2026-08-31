package com.remotly.app.terminal.fabric

import android.util.Base64
import com.facebook.react.bridge.ReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.Event
import com.remotly.app.terminal.TerminalView

// Bridges the TerminalView.Host callbacks to Fabric direct events. The Host
// callbacks fire on the main thread (the view drives them), which is what the
// event dispatcher expects. Byte payloads are base64-encoded here so the JS
// wrapper only ever sees strings at the bridge.
internal class FabricTerminalHost(
  private val reactContext: ReactContext,
  private val view: TerminalView,
) : TerminalView.Host {

  private fun surfaceId(): Int = UIManagerHelper.getSurfaceId(reactContext)

  private fun dispatch(event: Event<*>) {
    UIManagerHelper.getEventDispatcher(reactContext)?.dispatchEvent(event)
  }

  override fun onReady(cols: Int, rows: Int) {
    dispatch(TerminalReadyEvent(surfaceId(), view.id, cols, rows))
  }

  override fun onSelectionChange(active: Boolean) {
    dispatch(TerminalSelectionEvent(surfaceId(), view.id, active))
  }

  override fun onPasteRequest() {
    dispatch(TerminalPasteEvent(surfaceId(), view.id))
  }

  override fun onError(code: String) {
    dispatch(TerminalErrorEvent(surfaceId(), view.id, code))
  }

  override fun onInput(data: ByteArray) {
    dispatch(TerminalInputEvent(surfaceId(), view.id, Base64.encodeToString(data, Base64.NO_WRAP)))
  }

  override fun onResize(cols: Int, rows: Int) {
    dispatch(TerminalResizeEvent(surfaceId(), view.id, cols, rows))
  }

  override fun onBell() {
    dispatch(TerminalBellEvent(surfaceId(), view.id))
  }

  override fun onTitle(title: String) {
    dispatch(TerminalTitleEvent(surfaceId(), view.id, title))
  }

  override fun onFontSizeChange(fontSizeSp: Int) {
    dispatch(TerminalFontSizeEvent(surfaceId(), view.id, fontSizeSp))
  }

  override fun onFocusChange(focused: Boolean) {
    dispatch(TerminalFocusEvent(surfaceId(), view.id, focused))
  }

  override fun onPtyWrite(data: ByteArray) {
    dispatch(
      TerminalPtyWriteEvent(surfaceId(), view.id, Base64.encodeToString(data, Base64.NO_WRAP))
    )
  }
}
