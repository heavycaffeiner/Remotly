package com.remotly.app.terminal.fabric

import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.facebook.react.bridge.ReactContext
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.ViewManagerDelegate
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.viewmanagers.RemotlyTerminalViewManagerDelegate
import com.facebook.react.viewmanagers.RemotlyTerminalViewManagerInterface
import com.remotly.app.terminal.TerminalView

/**
 * Fabric ViewManager for the native terminal (RN-06). Wraps the existing
 * [TerminalView] (libghostty-vt) without modifying its rendering or IME logic.
 * Props and commands arrive on the UI thread; events are forwarded by
 * [FabricTerminalHost].
 */
@ReactModule(name = RemotlyTerminalViewManager.NAME)
class RemotlyTerminalViewManager :
  SimpleViewManager<TerminalView>(),
  RemotlyTerminalViewManagerInterface<TerminalView> {

  // The codegen'd delegate routes prop updates to the interface setters.
  private val delegate: ViewManagerDelegate<TerminalView> =
    RemotlyTerminalViewManagerDelegate(this)

  override fun getDelegate(): ViewManagerDelegate<TerminalView> = delegate

  override fun getName(): String = NAME

  override fun createViewInstance(context: ThemedReactContext): TerminalView {
    val view = TerminalView(context)
    view.host = FabricTerminalHost(context, view)
    return view
  }

  // Never recycle: a TerminalView owns a native ghostty handle and an IME
  // session; recycling would destroy both mid-use. Returning null is the
  // documented opt-out.
  override fun prepareToRecycleView(context: ThemedReactContext, view: TerminalView): TerminalView? =
    null

  // The native handle lives until React drops the view, not until the view
  // leaves the window. Detach happens on every navigation away, and releasing
  // there discarded the session's scrollback.
  override fun onDropViewInstance(view: TerminalView) {
    view.host = null
    view.release()
    super.onDropViewInstance(view)
  }

  @ReactProp(name = "fontSize")
  override fun setFontSize(view: TerminalView, value: Int) {
    if (value > 0) view.setFontSizeSp(value.toFloat())
  }

  @ReactProp(name = "cursorStyle")
  override fun setCursorStyle(view: TerminalView, value: String?) {
    // An unrecognized value falls back to block rather than failing the prop.
    view.cursorStyle = when (value) {
      "bar" -> TerminalView.CursorStyle.BAR
      "underline" -> TerminalView.CursorStyle.UNDERLINE
      else -> TerminalView.CursorStyle.BLOCK
    }
  }

  @ReactProp(name = "sessionId")
  override fun setSessionId(view: TerminalView, value: String?) {
    // Identifies which retained terminal this view renders. A terminal kept
    // under this id is adopted with its scrollback intact rather than a fresh
    // one being created.
    view.sessionId = value ?: ""
  }

  override fun write(view: TerminalView, dataB64: String) {
    runOnMain { view.feed(Base64.decode(dataB64, Base64.NO_WRAP)) }
  }

  // Delegates to the view, which shows the IME unconditionally. The previous
  // version only did so when requestFocus returned true, so a view that was
  // already focused (the state after the user dismisses the keyboard) got
  // nothing at all.
  override fun focusTerminal(view: TerminalView) {
    runOnMain { view.openKeyboard() }
  }

  override fun hideKeyboard(view: TerminalView) {
    runOnMain { view.hideKeyboard() }
  }

  override fun selectAll(view: TerminalView) {
    runOnMain { view.selectAll() }
  }

  override fun scrollByRows(view: TerminalView, rows: Int) {
    runOnMain { view.scrollByRows(rows) }
  }

  override fun scrollToBottom(view: TerminalView) {
    runOnMain { view.scrollToBottom() }
  }

  override fun clearSelection(view: TerminalView) {
    runOnMain { view.clearSelection() }
  }

  override fun clearComposition(view: TerminalView) {
    runOnMain { view.clearComposition() }
  }

  override fun applyRemoteSize(view: TerminalView, cols: Int, rows: Int) {
    runOnMain { view.applyRemoteSize(cols, rows) }
  }

  override fun copySelection(view: TerminalView) {
    val reactContext = view.context as? ReactContext ?: return
    runOnMain {
      val text = view.copySelection()
      val surfaceId = UIManagerHelper.getSurfaceId(reactContext)
      UIManagerHelper.getEventDispatcher(reactContext)
        ?.dispatchEvent(TerminalCopyEvent(surfaceId, view.id, text != null, text ?: ""))
    }
  }

  // libghostty-vt is main-thread only. Fabric delivers commands on the UI
  // thread, but keep an explicit guard.
  private fun runOnMain(r: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) r() else mainHandler.post { r() }
  }

  companion object {
    const val NAME = "RemotlyTerminalView"
    private val mainHandler = Handler(Looper.getMainLooper())
  }
}
