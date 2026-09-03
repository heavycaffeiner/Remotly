package com.remotly.app.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import android.text.InputType
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.remotly.app.BuildConfig
import com.remotly.app.R

/**
 * The Android view backed by libghostty-vt (M1-09). Owns the native terminal
 * handle, renders frames to a Canvas, and captures input (IME + hardware keys).
 * All native calls happen on the main thread.
 *
 * [host] receives terminal events (ready, input, resize, bell, title) so the
 * surrounding Fabric view manager can forward them to the app.
 */
class TerminalView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr), RemotlyTerminal.Listener {

  interface Host {
    fun onReady(cols: Int, rows: Int)
    fun onError(code: String)
    fun onInput(data: ByteArray)
    fun onResize(cols: Int, rows: Int)
    fun onBell()
    fun onTitle(title: String)
    /** A pinch settled on a new whole-sp font size. */
    fun onFontSizeChange(fontSizeSp: Int)

    /** Terminal focus changed. JS needs this to apply its keyboard policy. */
    fun onFocusChange(focused: Boolean)
    fun onPtyWrite(data: ByteArray)

    /** A selection was made or dropped, so the screen can offer Copy. */
    fun onSelectionChange(active: Boolean)

    /**
     * Paste was chosen from the selection toolbar.
     *
     * The clipboard is read on the JS side, which already owns sending input
     * to the session.
     */
    fun onPasteRequest()
  }

  var host: Host? = null

  private var handle: Long = 0L
  private var frame: TerminalFrame? = null

  /**
   * The session this view renders.
   *
   * Set from the `sessionId` prop before the view is measured. A terminal
   * retained under this id is adopted instead of a fresh one being created, so
   * the scrollback survives leaving and reopening the screen.
   */
  var sessionId: String = ""
    set(value) {
      if (field == value) return
      // Switching a mounted view to another session hands the current
      // terminal back rather than destroying it.
      if (handle != 0L && field.isNotEmpty()) {
        // Unbound first: this view no longer renders the old session, and
        // leaving it registered sends that session's repaints to a view now
        // showing something else.
        TerminalStore.unbindRenderer(field, this)
        TerminalStore.retain(field, handle)
        handle = 0L
        sessionReady = false
        // Emptied rather than dropped: the buffers are reused for the session
        // this view is being pointed at.
        frame?.reset()
        appliedCols = 0
        appliedRows = 0
      }
      field = value
      recomputeGrid()
    }
  private var composition: CompositionState = CompositionState.NONE

  /**
   * True when the application was tracking the mouse as this touch began.
   *
   * Sampled once on the press so a mode change mid-gesture cannot leave a
   * click half delivered.
   */
  private var mouseTracking = false

  /** Carries finger travel between moves so wheel notches pace evenly. */
  private val wheelTicker = WheelTicker()

  /** True while an accessibility announcement is posted but has not run. */
  private var accessibilityAnnouncePosted = false

  /** The size the terminal is actually running at, matching the pty. */
  private var appliedCols = 0
  private var appliedRows = 0
  private var inputConnection: TerminalInputConnection? = null
  private var sessionReady = false
  private var startupErrorReported = false

  // An explicit keyboard request that could not be served yet, because the
  // view was not attached. Replayed on attach; cleared on focus loss and
  // detach so it can never reopen a keyboard the user dismissed.
  private var pendingKeyboard = false
  private var keyboardRetryPosted = false
  private val tapDetector =
    TapDetector(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
  private val scrollTracker =
    ScrollTracker(ViewConfiguration.get(context).scaledTouchSlop.toFloat())
  private var velocityTracker: VelocityTracker? = null

  private val maxFlingVelocityPxPerSec: Float =
    ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
  private val fling = ScrollFling()
  private var flingFrameNanos = 0L

  // Where the finger left off, so a wheel report during a fling names the
  // cell the gesture was over. A report at the origin lands on whatever is in
  // the top-left corner, which is not what the user was pointing at.
  private var flingAnchorX = 0f
  private var flingAnchorY = 0f

  /** When the viewport last moved, so the scrollbar can fade out after it. */
  private var lastScrollAtMs = 0L

  /**
   * True when the next draw was scheduled only to advance the scrollbar fade.
   *
   * Such a draw reuses the frame it already has instead of serializing and
   * parsing the whole grid again, which is the most expensive thing this view
   * does and changes nothing while the terminal is idle. Cleared by every
   * other repaint path, so a write landing mid-fade is never drawn from the
   * frame that preceded it.
   */
  private var scrollbarFadeOnly = false

  /** True while a scrollbar fade frame is posted but has not run. */
  private var scrollbarFadePosted = false

  /**
   * Repaints to advance the scrollbar fade, without re-reading the terminal.
   *
   * Driven by its own frame callback rather than postInvalidateOnAnimation:
   * that routes back through [invalidate], which exists to clear the very flag
   * this sets.
   */
  private val scrollbarFadeCallback = Choreographer.FrameCallback {
    scrollbarFadePosted = false
    if (handle != 0L) {
      scrollbarFadeOnly = true
      superInvalidate()
    }
  }

  private fun superInvalidate() = super.invalidate()

  private fun scheduleScrollbarFade() {
    if (scrollbarFadePosted) return
    scrollbarFadePosted = true
    Choreographer.getInstance().postFrameCallback(scrollbarFadeCallback)
  }

  private fun cancelScrollbarFade() {
    if (!scrollbarFadePosted) return
    Choreographer.getInstance().removeFrameCallback(scrollbarFadeCallback)
    scrollbarFadePosted = false
  }


  // Driven by the choreographer so the fling advances once per displayed
  // frame, rather than at whatever rate a handler happens to fire.
  private val flingCallback = object : Choreographer.FrameCallback {
    override fun doFrame(frameTimeNanos: Long) {
      if (!fling.isRunning || handle == 0L) {
        flingFrameNanos = 0L
        return
      }
      val previous = flingFrameNanos
      flingFrameNanos = frameTimeNanos
      // The first frame has no interval to measure against.
      if (previous != 0L) {
        val seconds = (frameTimeNanos - previous) / 1_000_000_000f
        val rows = fling.advance(seconds.coerceAtMost(MAX_FRAME_SECONDS), cellHeightPx)
        // The fling reports the wheel too, so momentum keeps scrolling a
        // full-screen application rather than stopping when the finger lifts.
        // Whether to report is captured when the fling starts: the touch that
        // launched it has already ended and cleared mouseTracking.
        if (rows != 0) {
          scrollOrReportWheel(rows, flingAnchorX, flingAnchorY, flingReportsWheel)
        }
      }
      if (fling.isRunning) Choreographer.getInstance().postFrameCallback(this)
      else flingFrameNanos = 0L
    }
  }

  /** True when the gesture that launched the fling was reporting the wheel. */
  private var flingReportsWheel = false

  private fun startFling(velocityPxPerSec: Float, reportsWheel: Boolean) {
    stopFling()
    // ydpi can be reported as zero or nonsense on emulators, so fall back to
    // the density bucket, which is always present.
    val dm = resources.displayMetrics
    val ppi = if (dm.ydpi > 20f) dm.ydpi else dm.density * 160f
    fling.start(velocityPxPerSec, ppi)
    flingReportsWheel = reportsWheel && fling.isRunning
    if (fling.isRunning) Choreographer.getInstance().postFrameCallback(flingCallback)
  }

  private fun stopFling() {
    if (fling.isRunning || flingFrameNanos != 0L) {
      Choreographer.getInstance().removeFrameCallback(flingCallback)
    }
    fling.stop()
    flingFrameNanos = 0L
    flingReportsWheel = false
  }
  private val scaleDetector = ScaleGestureDetector(
    context,
    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
      override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        tapDetector.onPointerDown()
        return true
      }

      override fun onScale(detector: ScaleGestureDetector): Boolean {
        val nextSp = TerminalZoom.scale(fontSizePx / spToPx(1f), detector.scaleFactor)
        fontSizePx = spToPx(nextSp)
        return true
      }

      override fun onScaleEnd(detector: ScaleGestureDetector) {
        val settled = TerminalZoom.settle(fontSizePx / spToPx(1f))
        fontSizePx = spToPx(settled.toFloat())
        host?.onFontSizeChange(settled)
      }
    },
  ).apply {
    // Quick scale is on by default above API 23, and it turns a double tap
    // followed by a drag into a zoom. On a terminal that gesture is a tap to
    // open the keyboard and then a scroll, so the font size changed when the
    // user was only scrolling. Zooming stays a two-finger pinch, which is
    // unambiguous.
    isQuickScaleEnabled = false
    // The same for a stylus press-and-drag, which is a selection here.
    isStylusScaleEnabled = false
  }

  var fontSizePx: Float
    get() = renderer.fontSizePx
    set(value) {
      // Fabric re-applies every prop on each render. Remeasuring the cell and
      // recomputing the grid for a value that did not change costs a text
      // measure and can emit a resize, which a full-screen application answers
      // by repainting.
      if (renderer.fontSizePx == value) return
      renderer.fontSizePx = value
      recomputeGrid()
    }

  /** Sets the font size in scaled pixels, which is what the prop carries. */
  fun setFontSizeSp(sp: Float) {
    fontSizePx = spToPx(sp)
  }

  /** Cursor shape. Block is the default; the others come from settings. */
  enum class CursorStyle { BLOCK, BAR, UNDERLINE }

  var cursorStyle: CursorStyle = CursorStyle.BLOCK
    set(value) {
      if (field == value) return
      field = value
      invalidate()
    }

  /**
   * Scaled-pixel size in pixels.
   *
   * DisplayMetrics.scaledDensity is deprecated and does not carry non-linear
   * font scaling, which large accessibility font sizes use. Resolving through
   * TypedValue picks up whatever the platform applies.
   */
  private fun spToPx(sp: Float): Float =
    android.util.TypedValue.applyDimension(
      android.util.TypedValue.COMPLEX_UNIT_SP,
      sp,
      resources.displayMetrics,
    ).coerceAtLeast(0.1f)

  /** Owns the paints, the faces, and the cell grid this view draws into. */
  private val renderer = TerminalRenderer(context, resources.displayMetrics.density)

  // Reused across anchor updates: composition reports one per keystroke.
  private val anchorMatrix = Matrix()
  private val metrics: CellMetrics get() = renderer.metrics
  private val cellWidthPx: Int get() = renderer.cellWidthPx
  private val cellHeightPx: Int get() = renderer.cellHeightPx
  private var cols = 0
  private var rows = 0

  init {
    setBackgroundColor(Color.BLACK)
    isFocusable = true
    isFocusableInTouchMode = true
    // The view paints its own text, so nothing here reaches the accessibility
    // tree by itself. It is named and marked as a live region, and its
    // contents are supplied by onInitializeAccessibilityNodeInfo.
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    contentDescription = context.getString(R.string.terminal_view_label)
    accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    renderer.fontSizePx = spToPx(DEFAULT_FONT_SP)
  }

  /**
   * Exposes the visible screen to accessibility services.
   *
   * A screen reader has nothing to read otherwise: every glyph is painted onto
   * a Canvas. The viewport is flattened to lines here, trailing blanks
   * dropped, so the reader announces the text a sighted user sees rather than
   * a grid padded to its full width.
   */
  override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
    super.onInitializeAccessibilityNodeInfo(info)
    info.className = TerminalView::class.java.name
    info.isEditable = true
    val screen = viewportText()
    if (screen.isNotEmpty()) info.text = screen
  }

  /**
   * Announces output that arrived while the screen reader is on.
   *
   * Coalesced to one announcement per idle moment: a repainting TUI otherwise
   * interrupts the reader on every write and nothing is ever heard in full.
   */
  private fun scheduleAccessibilityAnnounce() {
    if (accessibilityAnnouncePosted) return
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    if (am == null || !am.isEnabled) return
    accessibilityAnnouncePosted = true
    postDelayed({
      accessibilityAnnouncePosted = false
      if (!isAttachedToWindow) return@postDelayed
      sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }, ACCESSIBILITY_ANNOUNCE_MS)
  }

  /**
   * The visible screen as text, one line per row.
   *
   * Reads the last parsed frame rather than the terminal, so it costs nothing
   * beyond the frame a draw already produced.
   */
  fun viewportText(): String {
    val f = frame ?: return ""
    if (f.cols <= 0 || f.rows <= 0) return ""
    val out = StringBuilder(f.rows * (f.cols + 1))
    val line = StringBuilder(f.cols)
    for (y in 0 until f.rows) {
      line.setLength(0)
      for (x in 0 until f.cols) {
        val i = f.indexOf(x, y)
        if (f.isSpacer(i)) continue
        val len = f.textLengthAt(i)
        if (len == 0) line.append(' ')
        else line.append(f.chars, f.textOffsetAt(i), len)
      }
      // Trailing blanks are grid padding, not content.
      var end = line.length
      while (end > 0 && line[end - 1] == ' ') end--
      out.append(line, 0, end)
      if (y < f.rows - 1) out.append('\n')
    }
    return out.toString().trimEnd('\n')
  }

  /**
   * Declares the view as a text editor.
   *
   * Without this the platform treats it as an ordinary view, and
   * showSoftInput is refused for a target that is not an editor. It is also
   * what makes restartInput meaningful after a connection goes stale.
   */
  override fun onCheckIsTextEditor(): Boolean = true

  override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
    super.onFocusChanged(focused, direction, previouslyFocusedRect)
    // Gaining focus no longer opens the keyboard on its own. Focus can be
    // gained without the user asking to type, and reopening then is what made
    // a dismissed keyboard spring back.
    host?.onFocusChange(focused)
    if (!focused) {
      pendingKeyboard = false
    }
  }

  /**
   * Requests focus and opens the software keyboard.
   *
   * The failing case this exists for: the view still holds focus after the
   * user dismissed the keyboard, so requestFocus returns true, no
   * focus-changed callback fires, and nothing shows the IME. Every path here
   * calls showSoftInput itself rather than relying on a focus transition.
   *
   * Returns whether the request was accepted for delivery.
   */
  fun openKeyboard(): Boolean {
    if (!isAttachedToWindow || windowToken == null) {
      // Ask again once the view is attached, rather than dropping the request.
      pendingKeyboard = true
      return false
    }
    if (!isFocused) requestFocus()

    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
      ?: return false

    // A connection left over from a previous IME session can be stale, which
    // shows as a keyboard that appears but delivers nothing.
    if (inputConnection == null || !imm.isActive(this)) {
      imm.restartInput(this)
    }

    if (imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)) {
      pendingKeyboard = false
      return true
    }

    // One retry on the next frame, for the window that has focus but has not
    // finished its input connection handshake. Bounded deliberately: a loop
    // here would spin whenever the platform legitimately refuses.
    if (!keyboardRetryPosted) {
      keyboardRetryPosted = true
      post {
        keyboardRetryPosted = false
        if (isAttachedToWindow && windowToken != null) {
          if (!isFocused) requestFocus()
          imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
      }
    }
    return false
  }

  /** Hides the software keyboard without dropping terminal focus. */
  fun hideKeyboard() {
    pendingKeyboard = false
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
      ?: return
    imm.hideSoftInputFromWindow(windowToken, 0)
  }

  /**
   * A one-finger tap opens the keyboard; a one-finger drag scrolls the
   * scrollback; a long press starts a selection that the same finger then
   * drags out; two fingers is a pinch and changes the font size.
   *
   * A pinch must not raise the IME or scroll, and accessibility exploration
   * does not arrive here at all.
   */
  override fun onTouchEvent(event: MotionEvent): Boolean {
    scaleDetector.onTouchEvent(event)
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        // A touch during a fling catches the content, as every scroller does.
        stopFling()
        // A grab on either handle adjusts the existing selection rather than
        // starting a new gesture.
        val grabbed = selection?.let {
          handleAt(it, event.x, event.y, cellWidthPx, cellHeightPx, handleRadiusPx)
        }
        if (grabbed != null) {
          // The grabbed end becomes the one that moves; the opposite end is
          // pinned. Either handle can extend or shrink the range.
          selection = selection?.grabbing(grabbed)
          selecting = true
          parent?.requestDisallowInterceptTouchEvent(true)
          return true
        }
        // The button press is deliberately not sent yet. A touch is not a
        // click until it ends without having scrolled: sending it here made
        // every scroll begin with a click, so a drag over a list selected
        // whatever was under the finger before moving.
        //
        // Whether the application is tracking is still needed now, to decide
        // whether a drag becomes wheel reports. Asking the encoder costs
        // nothing and writes nothing.
        mouseTracking = mouseReportingActive()
        // Leftover travel belongs to the gesture that produced it.
        wheelTicker.reset()
        tapDetector.onDown(event.x, event.y)
        scrollTracker.onDown(event.y)
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain()
        velocityTracker?.addMovement(event)
        // A press held in place starts a selection. Any movement past the
        // touch slop cancels it, so a scroll is never mistaken for one.
        armSelectionLongPress(event.x, event.y)
        // The parent is a scrollable RN view; claim the gesture so it cannot
        // steal a vertical drag meant for the terminal's scrollback.
        parent?.requestDisallowInterceptTouchEvent(true)
      }
      MotionEvent.ACTION_POINTER_DOWN -> {
        tapDetector.onPointerDown()
        scrollTracker.onPointerDown()
        cancelSelectionLongPress()
        clearSelection()
      }
      MotionEvent.ACTION_MOVE -> {
        tapDetector.onMove(event.x, event.y)
        velocityTracker?.addMovement(event)
        if (selecting) {
          extendSelection(event.x, event.y)
        } else if (!scaleDetector.isInProgress) {
          if (!tapDetector.isCandidate) cancelSelectionLongPress()
          val rows = scrollTracker.onMove(event.y, cellHeightPx)
          // A drag scrolls, and nothing else. Motion is deliberately not
          // reported: no button has been pressed, so an application that
          // tracks drags would read it as one and select or move whatever the
          // finger passed over while the user was only scrolling.
          if (rows != 0) scrollOrReportWheel(rows, event.x, event.y, mouseTracking)
        }
      }
      MotionEvent.ACTION_UP -> {
        // A touch that never scrolled is a click, and this is the first point
        // at which that is known. Sending the press on ACTION_DOWN instead
        // made every scroll start with a click.
        if (mouseTracking && !scrollTracker.isScrolling && !selecting) {
          mouseTracking = false
          if (sendMouse(MOUSE_PRESS, MOUSE_BUTTON_LEFT, event.x, event.y)) {
            sendMouse(MOUSE_RELEASE, MOUSE_BUTTON_LEFT, event.x, event.y)
            scrollTracker.onUp()
            releaseVelocityTracker()
            cancelSelectionLongPress()
            parent?.requestDisallowInterceptTouchEvent(false)
            return true
          }
        }
        // Captured before it is cleared: a fling launched by this touch keeps
        // reporting the wheel to the application the gesture began on.
        val wasTracking = mouseTracking
        mouseTracking = false
        cancelSelectionLongPress()
        if (selecting) {
          // The selection stays up; the floating toolbar acts on it. Its
          // position is recomputed now that the drag has settled.
          selecting = false
          scrollTracker.onUp()
          releaseVelocityTracker()
          parent?.requestDisallowInterceptTouchEvent(false)
          if (actionMode == null) startActionMode()
          else actionMode?.invalidateContentRect()
          return true
        }
        val tracker = velocityTracker
        if (scrollTracker.isScrolling && tracker != null) {
          // Capped at the platform maximum: an unbounded computation can spike
          // on a flick that ends with two samples a millisecond apart.
          tracker.computeCurrentVelocity(1000, maxFlingVelocityPxPerSec)
          // Dragging down reveals older output, so the fling carries the same
          // sign the drag did.
          flingAnchorX = event.x
          flingAnchorY = event.y
          startFling(tracker.yVelocity, wasTracking)
        }
        scrollTracker.onUp()
        releaseVelocityTracker()
        parent?.requestDisallowInterceptTouchEvent(false)
        if (tapDetector.onUp(event.pointerCount)) {
          // A tap dismisses a selection rather than opening the keyboard on
          // top of one.
          if (hasSelection) {
            clearSelection()
            return true
          }
          performClick()
          return true
        }
      }
      MotionEvent.ACTION_CANCEL -> {
        // No press was sent, so there is no release owed: a cancelled touch
        // simply never became a click.
        mouseTracking = false
        wheelTicker.reset()
        tapDetector.onCancel()
        scrollTracker.onCancel()
        cancelSelectionLongPress()
        selecting = false
        releaseVelocityTracker()
        parent?.requestDisallowInterceptTouchEvent(false)
      }
    }
    return true
  }

  // --- Selection ------------------------------------------------------------

  /** True while a drag is moving one end of the selection. */
  private var selecting = false

  /** True while a selection is installed and copyable. */
  var hasSelection = false
    private set

  private var selection: TerminalSelection? = null
  private var selectionLongPress: Runnable? = null
  private var actionMode: ActionMode? = null
  private val handleRadiusPx =
    ViewConfiguration.get(context).scaledTouchSlop * 2f


  private fun armSelectionLongPress(x: Float, y: Float) {
    cancelSelectionLongPress()
    val runnable = Runnable {
      selectionLongPress = null
      if (handle == 0L) return@Runnable
      // The scroll tracker owns the same finger; releasing it here stops the
      // drag from scrolling while it selects.
      scrollTracker.onCancel()
      tapDetector.onCancel()
      stopFling()
      performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
      // A long press selects the word under it, the way a text view does, so
      // one press already yields something copyable.
      val col = colAt(x)
      val row = rowAt(y)
      selecting = true
      applySelection(selectWordAt(col, row) ?: TerminalSelection.at(col, row))
      startActionMode()
    }
    selectionLongPress = runnable
    postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
  }

  private fun cancelSelectionLongPress() {
    selectionLongPress?.let { removeCallbacks(it) }
    selectionLongPress = null
  }

  /** Word bounds under a cell, or null when the terminal reports none. */
  private fun selectWordAt(col: Int, row: Int): TerminalSelection? {
    if (handle == 0L) return null
    val bounds = RemotlyTerminal.nativeSelectWord(handle, col, row) ?: return null
    if (bounds.size < 4) return null
    return TerminalSelection(bounds[0], bounds[1], bounds[2], bounds[3])
  }

  private fun extendSelection(x: Float, y: Float) {
    val current = selection ?: return
    applySelection(current.withFocus(colAt(x), rowAt(y)))
  }

  private fun applySelection(next: TerminalSelection) {
    if (handle == 0L) return
    val ok = RemotlyTerminal.nativeSelectRange(
      handle, next.startCol, next.startRow, next.endCol, next.endRow, false,
    )
    if (!ok) return
    selection = next
    hasSelection = true
    invalidate()
    // The floating toolbar follows the selection as it grows.
    actionMode?.invalidateContentRect()
  }

  /**
   * Reports a touch to an application that asked for mouse tracking.
   *
   * Returns false when it wanted nothing, which is every ordinary shell, and
   * the caller keeps the gesture for scrolling and selection.
   */
  /**
   * Scrolls the viewport, or reports a wheel to an application tracking it.
   *
   * A full-screen application draws on the alternate screen and keeps no
   * scrollback, so moving the local viewport does nothing a user can see: its
   * list stays where it was. Those applications scroll by reading wheel
   * reports, which is what a desktop terminal sends and what this view never
   * did, so scrolling did not work in any of them.
   *
   * An application that wants no mouse reports produces no bytes, and the
   * gesture falls through to the viewport as before, which is what an
   * ordinary shell needs.
   */
  private fun scrollOrReportWheel(rows: Int, x: Float, y: Float, reportWheel: Boolean) {
    if (rows == 0) return
    // A viewport already showing scrollback keeps the gesture, even when the
    // application is reading mouse reports. Otherwise a user who scrolled up
    // on the primary screen had every further gesture handed to the
    // application and no way back down: the wheel scrolled its list while the
    // view stayed in the history.
    //
    // The first gesture back into scrollback is kept too, but only from the
    // state where the user is otherwise stuck: pinned at the bottom with
    // history above. Reaching the bottom handed every later gesture to the
    // application, including the one that would have scrolled back up, so the
    // history was unreachable until the session was reopened. An application
    // on the alternate screen has no history above the active area and is
    // unaffected; one on the primary screen keeps every downward gesture and
    // every gesture once the view has left the bottom.
    if (!reportWheel || !atBottom() || (rows > 0 && hasScrollback())) {
      scrollByRows(rows)
      return
    }
    // A notch is worth several rows to the application receiving it, so the
    // rows are paced rather than sent one report each. Leftover travel is
    // carried, which is what keeps a slow drag moving at all.
    val notches = wheelTicker.advance(rows)
    if (notches == 0) return
    val button = WheelReport.button(notches)
    var reported = false
    repeat(kotlin.math.abs(notches)) {
      if (sendMouse(MOUSE_PRESS, button, x, y)) reported = true
    }
    // The application stopped tracking mid-gesture. Fall back rather than
    // swallow the scroll.
    if (!reported) scrollByRows(rows)
  }

  /** True when the running application is listening for mouse reports. */
  private fun mouseReportingActive(): Boolean =
    handle != 0L && RemotlyTerminal.nativeMouseReporting(handle)

  private fun sendMouse(action: Int, button: Int, x: Float, y: Float): Boolean {
    if (handle == 0L || cellWidthPx <= 0 || cellHeightPx <= 0) return false
    val col = (x / cellWidthPx).toInt().coerceAtLeast(0)
    val row = (y / cellHeightPx).toInt().coerceAtLeast(0)
    return RemotlyTerminal.nativeSendMouse(
      handle,
      action,
      button,
      0,
      col,
      row,
      cellWidthPx,
      cellHeightPx,
    )
  }

  /**
   * Drops an open IME preedit without sending it, and tells the IME so its
   * own buffer does not stay out of step with the overlay.
   */
  fun clearComposition() {
    val connection = inputConnection ?: return
    if (!connection.isComposing) return
    connection.abandonComposition()
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.restartInput(this)
  }

  /**
   * Drops the active selection.
   *
   * Reentrant: finishing the action mode calls back into onDestroyActionMode,
   * which clears again. The state is torn down before finish() so the second
   * pass sees nothing to do.
   */
  fun clearSelection() {
    cancelSelectionLongPress()
    selecting = false
    val had = hasSelection
    if (handle != 0L && had) RemotlyTerminal.nativeClearSelection(handle)
    hasSelection = false
    selection = null
    val mode = actionMode
    actionMode = null
    mode?.finish()
    if (had) {
      invalidate()
      host?.onSelectionChange(false)
    }
  }

  /**
   * Starts Android's floating selection toolbar.
   *
   * TYPE_FLOATING is what puts Copy beside the selection rather than in a bar
   * across the top, which is the behavior every other text surface on the
   * platform has.
   */
  private fun startActionMode() {
    actionMode?.finish()
    actionMode = startActionMode(selectionCallback, ActionMode.TYPE_FLOATING)
    host?.onSelectionChange(true)
  }

  private val selectionCallback = object : ActionMode.Callback2() {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
      // The platform's own strings and icons, so this reads as the system
      // selection toolbar it is.
      menu.add(Menu.NONE, MENU_COPY, 0, android.R.string.copy)
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
      menu.add(Menu.NONE, MENU_PASTE, 1, android.R.string.paste)
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
      menu.add(Menu.NONE, MENU_SELECT_ALL, 2, android.R.string.selectAll)
        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
      return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean =
      when (item.itemId) {
        MENU_COPY -> {
          copySelection()
          clearSelection()
          true
        }
        MENU_PASTE -> {
          host?.onPasteRequest()
          clearSelection()
          true
        }
        MENU_SELECT_ALL -> {
          selectAll()
          true
        }
        else -> false
      }

    override fun onDestroyActionMode(mode: ActionMode) {
      actionMode = null
      // Dismissing the toolbar drops the selection, matching a text view.
      if (hasSelection) clearSelection()
    }

    // Positions the toolbar around the selection instead of over it.
    override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
      val current = selection
      if (current == null) {
        super.onGetContentRect(mode, view, outRect)
        return
      }
      val bounds = selectionBounds(current, cellWidthPx, cellHeightPx)
      outRect.set(bounds[0], bounds[1], bounds[2], bounds[3])
    }
  }

  // Viewport cell under a touch, clamped so a drag past the edge selects to
  // the edge rather than failing to resolve.
  private fun colAt(x: Float): Int {
    if (cellWidthPx <= 0) return 0
    val maxCol = (frame?.cols ?: cols) - 1
    return (x / cellWidthPx).toInt().coerceIn(0, maxCol.coerceAtLeast(0))
  }

  private fun rowAt(y: Float): Int {
    if (cellHeightPx <= 0) return 0
    val maxRow = (frame?.rows ?: rows) - 1
    return (y / cellHeightPx).toInt().coerceIn(0, maxRow.coerceAtLeast(0))
  }

  private fun releaseVelocityTracker() {
    velocityTracker?.recycle()
    velocityTracker = null
  }

  /**
   * Scrolls the viewport by whole rows.
   *
   * Positive moves toward the scrollback, matching the direction content moves
   * under the finger. A terminal running the alternate screen has no
   * scrollback and libghostty keeps the viewport pinned, so this does nothing
   * there rather than scrolling behind the application.
   *
   * A downward scroll that lands within a row of the end pins to the active
   * area instead of stopping where the delta happened to fall. The delta is
   * relative, so against an application still writing, the end moves further
   * away while the gesture is being applied: the viewport crept toward a
   * target it never reached and the user could not get back to the prompt.
   */
  fun scrollByRows(rows: Int) {
    if (handle == 0L || rows == 0) return
    if (rows < 0 && withinSnapOfBottom(-rows)) {
      scrollToBottom()
      // Nothing is left to travel, and letting the fling run on would keep
      // asking for rows the viewport no longer has.
      stopFling()
      return
    }
    RemotlyTerminal.nativeScrollViewport(handle, -rows)
    lastScrollAtMs = SystemClock.uptimeMillis()
    invalidate()
  }

  /**
   * True when scrolling down by rows would land at or past the last row.
   *
   * A geometry that cannot be read answers false, so the scroll is attempted
   * rather than snapped.
   */
  private fun withinSnapOfBottom(rows: Int): Boolean =
    ScrollGeometry.withinSnapOfBottom(scrollbar(), rows)

  /**
   * Pins the viewport back to the active area.
   *
   * Called for the user's own "take me back to the prompt" actions: typing, a
   * resize, and the scroll-to-bottom affordance. Output does not call this.
   * libghostty already keeps the viewport on the active area as it writes, and
   * overriding that broke applications that redraw in place by moving the
   * cursor up.
   *
   * Every committed key asks for this, so it returns without touching the
   * terminal when the viewport is already on the active area. Stamping
   * lastScrollAtMs unconditionally instead restarted the scrollbar fade on
   * each key, and a fading bar posts its own frame callback: typing then held
   * a repaint loop open for as long as the user kept typing, which is felt as
   * the terminal lagging behind the keyboard.
   */
  fun scrollToBottom() {
    if (!ScrollGeometry.pinNeeded(handle, scrollbar())) return
    RemotlyTerminal.nativeScrollToBottom(handle)
    lastScrollAtMs = SystemClock.uptimeMillis()
    invalidate()
  }

  /**
   * True when the viewport is away from the active area, so the owning screen
   * can offer a way back.
   *
   * A full-screen application that reads mouse reports takes every scroll
   * gesture, so a user who scrolled the local viewport first had no gesture
   * left to return with: the wheel goes to the application, not the viewport.
   */
  fun isScrolledBack(): Boolean = handle != 0L && !atBottom()

  /** True when the viewport already shows the active area. */
  private fun atBottom(): Boolean = ScrollGeometry.atBottom(scrollbar())

  /**
   * True when rows exist above the active area.
   *
   * The alternate screen keeps none, so a full-screen application is told
   * apart from a shell by this rather than by asking which screen is active.
   */
  private fun hasScrollback(): Boolean = ScrollGeometry.hasScrollback(scrollbar())

  /**
   * Scrollbar geometry as (total, offset, visible), or null when the terminal
   * cannot report it: no handle yet, or one released under a tab switch.
   *
   * Read fresh on every call. The row count changes as output arrives, and a
   * cached total is exactly what made the bottom unreachable before.
   */
  private fun scrollbar(): LongArray? =
    ScrollGeometry.of(RemotlyTerminal.nativeScrollbar(handle))

  // Routed through performClick so accessibility services can trigger the same
  // action they announce.
  override fun performClick(): Boolean {
    super.performClick()
    openKeyboard()
    return true
  }


  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    recomputeGrid()
  }

  private fun recomputeGrid() {
    // Fabric can expose a transient zero-sized layout during mount and
    // rotation. Do not create a bogus 1x1 terminal or emit ready for it.
    if (width <= 0 || height <= 0 || cellWidthPx <= 0 || cellHeightPx <= 0) return
    // A frame costs 73 bytes per cell and is rebuilt on every draw, so an
    // unbounded grid is an out-of-memory kill rather than a slow screen.
    // These bounds are far above any real phone layout.
    val newCols = (width / cellWidthPx).coerceIn(1, MAX_COLS)
    val newRows = (height / cellHeightPx).coerceIn(1, MAX_ROWS)
    if (handle == 0L) {
      cols = newCols
      rows = newRows
      createTerminal()
      return
    }
    // Republished whenever the terminal is not already running at the
    // measurement, not only when the measurement changes. This alone does not
    // recover a dropped apply: the size upstream still matches what the
    // scheduler recorded as sent, so it is deduped. Clearing that record is
    // what makes the retry happen, and this is what offers the size again
    // once it has been cleared.
    if (newCols != cols || newRows != rows ||
      appliedCols != newCols || appliedRows != newRows
    ) {
      cols = newCols
      rows = newRows
      // Only the measurement is published here. Resizing the local terminal
      // now would leave it a different size from the pty until the debounced
      // resize reaches the remote, and everything the application drew in
      // that window was positioned for the size it still believed in: an
      // absolute cursor move or a scroll region then lands in the wrong band
      // and later output overwrites what came before it. The terminal is
      // resized from applyRemoteSize, once both ends agree.
      host?.onResize(cols, rows)
    }
  }

  /**
   * Resizes the terminal to the size the pty has been told.
   *
   * Called after the resize has been sent to the pty, not after the pty has
   * acknowledged it: the send is fire-and-forget, so a brief window remains
   * where output produced for the old grid is parsed against the new one.
   * That window is the reason the size is never applied straight from
   * measurement, which would widen it to the whole debounce interval.
   *
   * The viewport needs no correction afterwards. A height change moves rows
   * between the screen and the scrollback, but libghostty keeps the viewport
   * on the active area across it, so the newest output stays visible. An
   * earlier version scrolled back by however much the row count grew, on the
   * theory that the grow padded the screen below the real content. The
   * padding is above the viewport, not below it: the view was already right
   * and the correction pushed it up into the scrollback, which is what left
   * the terminal stuck above its own output after the keyboard closed.
   */
  fun applyRemoteSize(remoteCols: Int, remoteRows: Int) {
    if (handle == 0L || remoteCols <= 0 || remoteRows <= 0) return
    if (remoteCols == appliedCols && remoteRows == appliedRows) return
    appliedCols = remoteCols
    appliedRows = remoteRows
    RemotlyTerminal.nativeResize(handle, remoteCols, remoteRows, cellWidthPx, cellHeightPx)
    invalidate()
  }

  private fun createTerminal() {
    if (handle != 0L) return
    if (!RemotlyTerminal.isAvailable()) {
      reportStartupError(RemotlyTerminal.unavailableCode())
      return
    }

    // A terminal retained for this session keeps its scrollback. Adopting it
    // is what makes returning to a session show its history instead of a
    // blank screen.
    val retained = TerminalStore.take(sessionId)
    val adopted = retained != 0L
    val h = if (adopted) {
      RemotlyTerminal.nativeRebind(retained, this)
      retained
    } else {
      val scrollbackBytes = 8L * 1024 * 1024
      RemotlyTerminal.nativeCreate(cols, rows, scrollbackBytes, this)
    }
    if (h == 0L) {
      reportStartupError("create_failed")
      return
    }
    handle = h
    if (sessionId.isNotEmpty()) {
      TerminalStore.retain(sessionId, h)
    }
    TerminalStore.bindRenderer(sessionId, this)
    if (adopted) {
      // An adopted terminal is already running at the size its pty was told,
      // with scrollback laid out for it. Resizing to this view's grid now
      // reflows that history against a width the remote does not have yet,
      // which rewraps every retained line and moves the viewport off the
      // active area. applyRemoteSize does it once both ends agree.
      appliedCols = RemotlyTerminal.nativeCols(h)
      appliedRows = RemotlyTerminal.nativeRows(h)
    } else {
      // A fresh terminal has nothing drawn against an older grid, and the
      // session is opened with this size.
      appliedCols = cols
      appliedRows = rows
      RemotlyTerminal.nativeResize(h, cols, rows, cellWidthPx, cellHeightPx)
    }
    // An adopted terminal already holds a screen. Drawing it now is what makes
    // a tab switch show its content on the first frame rather than the next
    // time something happens to repaint.
    onExternalWrite()
    if (!sessionReady) {
      sessionReady = true
      host?.onReady(cols, rows)
    }
  }

  /**
   * Releases the native terminal.
   *
   * Called from the view manager when React is finished with this view, not
   * when it merely leaves the window. Detaching happens on every navigation
   * away, and destroying the handle there threw away the scrollback: coming
   * back showed an empty terminal with the session's history gone.
   */
  fun release() {
    stopFling()
    cancelScrollbarFade()
    cancelSelectionLongPress()
    TerminalStore.unbindRenderer(sessionId, this)
    if (handle != 0L) {
      // Handed to the store rather than destroyed: the session may still be
      // running, and its scrollback has to be there when the user returns.
      // TerminalStore.release ends it when the session is actually closed.
      if (sessionId.isNotEmpty()) {
        TerminalStore.retain(sessionId, handle)
      } else {
        RemotlyTerminal.nativeDestroy(handle)
      }
      handle = 0L
    }
    sessionReady = false
    startupErrorReported = false
    // The frame and its buffer are kept: this view can be handed another
    // session, and reallocating a direct buffer per session is what the reuse
    // exists to avoid. Its contents are dropped so nothing reports the old
    // session's screen before the first draw.
    frame?.reset()
    composition = CompositionState.NONE
    inputConnection = null
    pendingKeyboard = false
    keyboardRetryPosted = false
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    // A posted frame callback outliving the view would keep scrolling a
    // terminal nobody is looking at.
    stopFling()
    cancelScrollbarFade()
    cancelSelectionLongPress()
    // The native handle deliberately survives: it owns the scrollback, and
    // this fires every time the user navigates away. Only the IME connection
    // is dropped, so uncommitted preedit does not reappear over a later
    // session.
    composition = CompositionState.NONE
    inputConnection = null
    // A request that never ran must not fire when the view is reattached to a
    // different screen.
    pendingKeyboard = false
    keyboardRetryPosted = false
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    // A detach/reattach can keep its old dimensions, so onSizeChanged may not
    // fire. The handle survives a detach, so this usually only re-reports the
    // existing grid; it still creates one after a release.
    recomputeGrid()
    // Re-announce the grid so the owning screen re-arms its data flow. The
    // terminal kept its scrollback across the detach, so this is a reattach
    // rather than a new session, and nothing is replayed into it.
    if (handle != 0L && sessionReady) host?.onReady(cols, rows)
    // Only an explicit request made before attachment is replayed. Attachment
    // on its own never opens the keyboard.
    if (pendingKeyboard) {
      pendingKeyboard = false
      openKeyboard()
    }
  }

  private fun reportStartupError(code: String) {
    if (startupErrorReported) return
    startupErrorReported = true
    host?.onError(code)
  }

  /**
   * Repaints after someone else wrote to this view's terminal.
   *
   * The store writes directly when output is routed around the view, so the
   * frame has to be scheduled here or the change stays off screen.
   */
  fun onExternalWrite() {
    if (handle == 0L) return
    scheduleFrame()
    scheduleAccessibilityAnnounce()
    if (!composition.isEmpty) updateCursorAnchor()
  }

  /**
   * Feeds session output into the terminal.
   *
   * Composition state is deliberately untouched. Remote output arriving mid
   * syllable must not cancel or duplicate what the user is composing, which is
   * routine when a shell echoes or a TUI repaints.
   *
   * Writes are parsed immediately but the repaint is deferred to the next
   * frame. Session attach replays the scrollback as a burst of small chunks,
   * and painting each one made the user watch the history scroll past; one
   * frame at the end shows the settled screen instead.
   *
   * The viewport is deliberately not forced to the bottom here. libghostty
   * already follows the cursor as output is written, and overriding that broke
   * applications that redraw in place on the primary screen: an agent that
   * moves the cursor up to repaint an overlay had the viewport yanked back to
   * the last row on the very next write, so the overlay never appeared.
   */
  fun feed(data: ByteArray) {
    if (data.isEmpty()) return
    if (handle == 0L) {
      // The view has not been measured yet, so it owns no terminal. Dropping
      // the bytes here loses them outright: the session has already sent them
      // and will not send them again, so the screen stays one write behind
      // until something later happens to redraw. The store writes them into
      // the terminal held for this session instead, creating it if this is the
      // first output, and the view adopts that terminal when it is measured.
      if (sessionId.isNotEmpty()) {
        TerminalStore.feed(sessionId, data, cols, rows) {}
      }
      return
    }
    RemotlyTerminal.nativeWrite(handle, data)
    scheduleFrame()
    scheduleAccessibilityAnnounce()
    // The cursor may have moved, so the candidate window has to follow it.
    if (!composition.isEmpty) updateCursorAnchor()
  }

  /**
   * Coalesces repaints to one per display frame.
   *
   * invalidate() already merges within a frame, but a burst that spans frames
   * paints each one. This keeps the terminal at the display's rate no matter
   * how the bytes arrive.
   */
  private fun scheduleFrame() {
    // The terminal changed, so the next draw must read it again even if a
    // scrollbar fade had already scheduled one.
    scrollbarFadeOnly = false
    cancelScrollbarFade()
    // Draws in the frame the choreographer is already preparing, and repeated
    // calls before it runs collapse into one. Posting a frame callback that
    // then called invalidate cost an extra frame on every write, which is felt
    // directly as echo lag while typing.
    postInvalidateOnAnimation()
  }

  /**
   * Every repaint other than the scrollbar fade arrives here.
   *
   * Clearing the flag centrally is what keeps the fade's frame reuse safe: a
   * caller cannot forget to, and a repaint that has any other reason reads the
   * terminal again.
   */
  override fun invalidate() {
    scrollbarFadeOnly = false
    super.invalidate()
  }

  override fun onVisibilityAggregated(isVisible: Boolean) {
    super.onVisibilityAggregated(isVisible)
    // A fade frame posted for a view nobody can see would keep waking the
    // choreographer.
    if (!isVisible) cancelScrollbarFade()
  }

  fun selectAll() {
    if (handle != 0L) RemotlyTerminal.nativeSelectAll(handle)
  }

  /** Copy the active selection to the system clipboard; returns the text. */
  fun copySelection(): String? {
    if (handle == 0L) return null
    val bytes = RemotlyTerminal.nativeGetSelectionText(handle) ?: return null
    if (bytes.isEmpty()) return null
    val text = bytes.toString(Charsets.UTF_8)
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
    return text
  }

  /**
   * Refreshes [frame] from the terminal.
   *
   * Native serializes into a buffer this view keeps, so a repaint costs no
   * allocation. A grid that outgrew the buffer reports the size it needs and
   * the frame is fetched again.
   *
   * Returns false when nothing could be read, in which case [frame] still
   * holds the last good screen.
   */
  private fun refreshFrame(): Boolean {
    if (handle == 0L) return false
    val f = frame ?: TerminalFrame().also { frame = it }
    var len = RemotlyTerminal.nativeGetFrame(handle, f.buffer)
    if (len < 0) {
      f.growBuffer(-len)
      len = RemotlyTerminal.nativeGetFrame(handle, f.buffer)
    }
    if (len <= 0) return false
    return f.parseFromBuffer(len)
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (handle == 0L) return
    // A repaint driven only by the scrollbar fade reuses the frame it already
    // has: the terminal did not change, and re-serializing the whole grid to
    // move a few pixels of alpha is the most expensive thing this view does.
    // A frame emptied by a session switch is refreshed regardless.
    val reuse = scrollbarFadeOnly && (frame?.cols ?: 0) > 0
    scrollbarFadeOnly = false
    val fresh = reuse || refreshFrame()
    val f = frame
    // Nothing to draw: either the terminal had no frame to give, or it was
    // emptied by a session switch. Returning here would leave the previous
    // session's pixels on the surface, which is what made two screens appear
    // mixed together after a tab change. Painted with the background instead.
    if (!fresh || f == null || f.cols <= 0 || f.rows <= 0) {
      canvas.drawColor(if (f != null && f.defaultBg != 0) f.defaultBg else Color.BLACK)
      return
    }
    renderer.drawFrame(canvas, f, cursorStyle, composing = !composition.isEmpty)
    renderer.drawComposingText(canvas, f, composition)
    selection?.let { renderer.drawSelectionHandles(canvas, it, handleRadiusPx) }
    drawScrollbar(canvas, f)
  }

  /** Draws the scrollbar and schedules the next fade frame if it is fading. */
  private fun drawScrollbar(canvas: Canvas, f: TerminalFrame) {
    val bar = RemotlyTerminal.nativeScrollbar(handle) ?: return
    if (bar.size < 3) return

    // Fades out once the user stops scrolling, so it does not sit over the
    // terminal permanently. Measured on the same clock the scroll was stamped
    // with, so a wall-clock adjustment cannot freeze or skip the fade.
    val sinceScroll = SystemClock.uptimeMillis() - lastScrollAtMs
    val gesturing = fling.isRunning || scrollTracker.isScrolling
    val alpha = when {
      gesturing -> 1f
      sinceScroll >= SCROLLBAR_FADE_MS -> 0f
      else -> 1f - sinceScroll.toFloat() / SCROLLBAR_FADE_MS
    }

    val drawn = renderer.drawScrollbar(
      canvas,
      f,
      TerminalRenderer.ScrollbarState(bar[0], bar[1], bar[2]),
      width,
      height,
      alpha,
    )

    // Drive the fade itself. A fling already redraws every frame, and a bar
    // held at full opacity by an active gesture needs no repaint of its own,
    // so only the fading window schedules one.
    if (drawn && !gesturing) scheduleScrollbarFade()
  }

  // --- Input ----------------------------------------------------------------

  override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
    // Deliberately not MULTI_LINE. With that flag the IME treats its Enter key
    // as "insert a newline" and delivers it as commitText("\n"), so the
    // terminal receives literal text instead of an Enter key press. A TUI such
    // as Claude Code reads that as a line continuation, which is the same thing
    // its own Ctrl+Enter binding produces.
    //
    // Without it the IME sends a real key event, which reaches sendKeyEvent and
    // is encoded as Enter.
    outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
      InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
      EditorInfo.IME_FLAG_NO_EXTRACT_UI or
      EditorInfo.IME_ACTION_NONE
    // Terminal input is not dictation material, so it is kept out of the
    // keyboard's personalized learning where the platform supports that.
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
      outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }

    val connection = TerminalInputConnection(this, terminalSink, trace = BuildConfig.DEBUG)
    inputConnection = connection
    return connection
  }

  // What the IME connection is allowed to do to this terminal.
  private val terminalSink = object : TerminalSink {
    override fun sendText(text: String) {
      if (handle != 0L && text.isNotEmpty()) {
        RemotlyTerminal.nativeSendText(handle, text)
      }
      // Typing returns to the prompt, the way a desktop terminal does. Leaving
      // the viewport in the scrollback would hide the user's own keystrokes.
      // A no-op when the view is already there, so an application drawing
      // above the last row is not disturbed.
      scrollToBottom()
      invalidate()
    }

    override fun sendKey(event: KeyEvent, composing: Boolean) {
      if (handle == 0L) return
      val enc = KeyMap.encode(event)
      RemotlyTerminal.nativeSendKey(handle, enc.key, enc.mods, enc.utf8, composing)
      scrollToBottom()
    }

    override fun onCompositionChanged(state: CompositionState) {
      composition = state
      invalidate()
      updateCursorAnchor()
    }

  }

  /**
   * Reports where the caret is, so candidate windows follow it.
   *
   * Without this the IME anchors its candidate list at the view origin, which
   * on a full-screen terminal puts it nowhere near the text being composed.
   */
  private fun updateCursorAnchor() {
    val f = frame ?: return
    if (!isAttachedToWindow || f.cols <= 0 || f.rows <= 0) return
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
      ?: return

    val cellsBefore =
      PreeditLayout.cellsBefore(composition.text, composition.selectionEndUtf16)
    var col = f.cursorX + cellsBefore
    var row = f.cursorY
    while (col >= f.cols && f.cols > 0) {
      col -= f.cols
      row += 1
    }

    // CursorAnchorInfo coordinates are local to this View. Supplying screen
    // coordinates causes OEM IMEs to transform the point twice; some versions
    // throw while laying out a candidate window outside their visible bounds.
    val localX = col * cellWidthPx.toFloat()
    val localTop = row * cellHeightPx.toFloat()
    val localBaseline = localTop + metrics.baselinePx
    val localBottom = localTop + cellHeightPx
    // The marker is view-local, so the IME needs the view-to-screen transform
    // to place its candidate window. build() rejects positional values without
    // it, which crashed the app on the first composed CJK keystroke.
    anchorMatrix.reset()
    transformMatrixToGlobal(anchorMatrix)
    val info = CursorAnchorInfo.Builder()
      .setMatrix(anchorMatrix)
      .setInsertionMarkerLocation(
        localX,
        localTop,
        localBaseline,
        localBottom,
        CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION,
      )
      .setComposingText(0, composition.text)
      .build()
    imm.updateCursorAnchorInfo(this, info)
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    // System navigation and device controls are owned by Android, never by
    // the remote PTY. Consuming Back here trapped users on the terminal screen
    // whenever the native view held focus.
    if (keyCode == KeyEvent.KEYCODE_BACK ||
      keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
      keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
      keyCode == KeyEvent.KEYCODE_VOLUME_MUTE ||
      keyCode == KeyEvent.KEYCODE_POWER
    ) return super.onKeyDown(keyCode, event)
    if (handle != 0L) {
      // A hardware key arriving during composition is marked, so the terminal
      // core can tell it apart from ordinary input.
      val composing = inputConnection?.isComposing == true
      val enc = KeyMap.encode(event)
      RemotlyTerminal.nativeSendKey(handle, enc.key, enc.mods, enc.utf8, composing)
      scrollToBottom()
      return true
    }
    return super.onKeyDown(keyCode, event)
  }

  // --- RemotlyTerminal.Listener (native effects, main thread) ----------------

  override fun onBell() {
    // A terminal bell is a notification the user asked the remote to send, so
    // it is felt as well as reported. Ignores the view's own haptic setting
    // for the same reason a long press does: this is the effect, not a hint.
    performHapticFeedback(
      HapticFeedbackConstants.CONFIRM,
      HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
    )
    host?.onBell()
  }

  override fun onTitle(titleUtf8: ByteArray) {
    host?.onTitle(titleUtf8.toString(Charsets.UTF_8))
  }

  override fun onInput(data: ByteArray) {
    host?.onInput(data)
  }

  override fun onPtyWrite(data: ByteArray) {
    host?.onPtyWrite(data)
  }

  private companion object {
    // Mouse actions and buttons, matching the native encoder.
    const val MOUSE_PRESS = 0
    const val MOUSE_RELEASE = 1
    const val MOUSE_BUTTON_LEFT = 1


    const val MAX_COLS = 512
    const val MAX_ROWS = 512

    const val DEFAULT_FONT_SP = 14f

    // Clamps the frame interval so a stalled frame does not advance a fling by
    // a huge jump when rendering resumes.
    const val MAX_FRAME_SECONDS = 0.05f

    const val MENU_COPY = 1
    const val MENU_PASTE = 2
    const val MENU_SELECT_ALL = 3

    /** How long the bar stays up after the last scroll, in ms. */
    const val SCROLLBAR_FADE_MS = 900L

    /** How long output settles before it is announced, in ms. */
    const val ACCESSIBILITY_ANNOUNCE_MS = 400L
  }
}
