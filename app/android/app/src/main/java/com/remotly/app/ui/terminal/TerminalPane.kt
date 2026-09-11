package com.remotly.app.ui.terminal

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.viewinterop.AndroidView
import com.remotly.app.platform.readClipboardText
import com.remotly.app.session.shouldClaimSwipe
import com.remotly.app.session.swipeDirection
import com.remotly.app.terminal.TerminalView
import com.remotly.app.ui.components.ErrorState
import com.remotly.app.ui.components.LoadingState
import kotlinx.coroutines.delay

/** A startup that never reports readiness in this window is treated as failed. */
private const val STARTUP_TIMEOUT_MS = 5_000L

/**
 * A one-finger sideways drag across the terminal, reported as the direction
 * from [com.remotly.app.session.swipeDirection].
 *
 * Tracked at [PointerEventPass.Initial] with absolute positions: the native
 * view consumes every touch, so the main pass reports no movement. Consuming
 * once the drag is clearly horizontal cancels the view's own gesture. A
 * second finger hands the gesture back, because that is the terminal's pinch.
 */
fun Modifier.terminalTabSwipe(enabled: Boolean, onSwipe: (Int) -> Unit): Modifier {
    if (!enabled) return this
    return pointerInput(onSwipe) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var claimed = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size > 1) return@awaitEachGesture
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                tracker.addPosition(change.uptimeMillis, change.position)
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (!change.pressed) {
                    if (claimed) {
                        change.consume()
                        val direction = swipeDirection(dx, tracker.calculateVelocity().x / 1000f)
                        if (direction != 0) onSwipe(direction)
                    }
                    return@awaitEachGesture
                }
                if (!claimed && shouldClaimSwipe(dx, dy)) claimed = true
                if (claimed) change.consume()
            }
        }
    }
}

/**
 * What a screen can ask of the terminal it is showing.
 *
 * The pane owns the native view's lifetime, so a screen reaches it through
 * this rather than holding the view. Every call is a no-op while no view is
 * mounted, which is the state between a session switch and the next layout.
 */
@Stable
class TerminalHandle internal constructor() {
    internal var view: TerminalView? = null

    /** Brings up the keyboard, focusing the terminal first if it has to. */
    fun openKeyboard(): Boolean = view?.openKeyboard() ?: false

    fun hideKeyboard() {
        view?.hideKeyboard()
    }
    /** Drops an open CJK or other IME preedit without sending it. */
    fun clearComposition() {
        view?.clearComposition()
    }

    fun selectAll() {
        view?.selectAll()
    }

    /** The selected text, or null when nothing is selected. */
    fun copySelection(): String? = view?.copySelection()

    /**
     * Writes text as a paste.
     *
     * Through the view rather than through the session's input, so a program
     * that asked for bracketed paste gets the whole block in one piece
     * instead of a run of Enter keys.
     */
    fun paste(text: String) {
        view?.pasteText(text)
    }
}

@Composable
fun rememberTerminalHandle(): TerminalHandle = remember { TerminalHandle() }

/**
 * Hosts the native [TerminalView] for one session.
 *
 * [sessionKey] must be the bare session id, never a composite such as
 * `hostId:sessionId`. [com.remotly.app.terminal.TerminalStore] keys retained
 * terminals and routed pty output by this id alone; a composite key makes the
 * view adopt an empty terminal while the real session's output sits under a
 * key nobody is rendering.
 *
 * The view is recreated, not rebound, whenever [sessionKey] changes. Rebinding
 * one [TerminalView] instance across a session switch, by reassigning its
 * `sessionId`, once let a second shell render over the first one's screen. A
 * fresh instance per session avoids that class of bug outright.
 */
@Composable
fun TerminalPane(
    sessionKey: String,
    fontSizeSp: Int,
    cursorStyle: String,
    onInput: (ByteArray) -> Unit,
    onPtyWrite: (ByteArray) -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    onLinkCopied: (String) -> Unit,
    onFontSizeChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    handle: TerminalHandle? = null,
    onReady: (() -> Unit)? = null,
    onNotify: ((title: String, body: String) -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null,
    onFocusChanged: ((Boolean) -> Unit)? = null,
    onSelectionChanged: ((Boolean) -> Unit)? = null,
) {
    val onInputState = rememberUpdatedState(onInput)
    val onPtyWriteState = rememberUpdatedState(onPtyWrite)
    val onResizeState = rememberUpdatedState(onResize)
    val onLinkCopiedState = rememberUpdatedState(onLinkCopied)
    val onFontSizeChangedState = rememberUpdatedState(onFontSizeChanged)
    val onReadyState = rememberUpdatedState(onReady)
    val onNotifyState = rememberUpdatedState(onNotify)
    val onDoubleTapState = rememberUpdatedState(onDoubleTap)
    val onFocusChangedState = rememberUpdatedState(onFocusChanged)
    val onSelectionChangedState = rememberUpdatedState(onSelectionChanged)

    // retryToken forces a fresh native view without a new session: a startup
    // failure or timeout retries by bumping this rather than by rebinding the
    // view that just failed.
    var retryToken by remember(sessionKey) { mutableIntStateOf(0) }
    val instanceKey = "$sessionKey#$retryToken"
    var ready by remember(instanceKey) { mutableStateOf(false) }
    var errorMessage by remember(instanceKey) { mutableStateOf<String?>(null) }
    var viewRef by remember(instanceKey) { mutableStateOf<TerminalView?>(null) }

    // A startup that never calls back within this window is shown as a
    // failure with a retry, rather than a spinner that never resolves.
    LaunchedEffect(instanceKey) {
        delay(STARTUP_TIMEOUT_MS)
        if (!ready) errorMessage = "The terminal did not start in time."
    }

    val cursor = remember(cursorStyle) { toCursorStyle(cursorStyle) }

    // The resize order matters: the pty is told first, and the terminal is
    // resized only once that has happened, so the two ends never disagree
    // about the grid. Otherwise a program's absolute cursor moves and scroll
    // regions land in the wrong band for the brief window the two sides
    // disagree.
    val resizeScheduler = remember(instanceKey) {
        ResizeScheduler(send = { size ->
            onResizeState.value(size.cols, size.rows)
            viewRef?.applyRemoteSize(size.cols, size.rows)
        })
    }
    DisposableEffect(instanceKey) { onDispose { resizeScheduler.cancel() } }

    Box(modifier = modifier.clipToBounds()) {
        val failure = errorMessage
        if (failure != null) {
            ErrorState(
                title = "Terminal failed to start",
                message = failure,
                onRetry = { retryToken += 1 },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            key(instanceKey) {
                AndroidView(
                    factory = { ctx ->
                        val view = TerminalView(ctx)
                        // The bare id, per the sessionKey contract above.
                        view.sessionId = sessionKey
                        view.cursorStyle = cursor
                        view.setFontSizeSp(fontSizeSp.toFloat())
                        view.host = object : TerminalView.Host {
                            override fun onReady(cols: Int, rows: Int) {
                                ready = true
                                onReadyState.value?.invoke()
                                // The first measurement arrives here, not
                                // through onResize, which the view emits only
                                // when the grid changes. A screen that waits
                                // for a real grid before opening its first
                                // session would otherwise wait forever.
                                resizeScheduler.report(GridSize(cols, rows))
                            }

                            override fun onError(code: String) {
                                errorMessage = code
                            }

                            override fun onInput(data: ByteArray) {
                                onInputState.value(data)
                            }

                            override fun onResize(cols: Int, rows: Int) {
                                resizeScheduler.report(GridSize(cols, rows))
                            }

                            override fun onBell() {
                                // The view already gives haptic feedback for a
                                // bell on its own; no separate surface is
                                // wired for it here.
                            }

                            override fun onTitle(title: String) {
                                // No screen in this contract owns a title bar;
                                // left for whichever one adds it.
                            }

                            override fun onFontSizeChange(fontSizeSp: Int) {
                                onFontSizeChangedState.value(fontSizeSp)
                            }

                            override fun onFocusChange(focused: Boolean) {
                                onFocusChangedState.value?.invoke(focused)
                            }
                            override fun onDoubleTap(): Boolean {
                                val callback = onDoubleTapState.value ?: return false
                                callback()
                                return true
                            }

                            override fun onPtyWrite(data: ByteArray) {
                                onPtyWriteState.value(data)
                            }

                            override fun onSelectionChange(active: Boolean) {
                                onSelectionChangedState.value?.invoke(active)
                            }

                            override fun onPasteRequest() {
                                val text = readClipboardText(ctx)
                                if (!text.isNullOrEmpty()) view.pasteText(text)
                            }

                            override fun onNotify(title: String, body: String) {
                                onNotifyState.value?.invoke(title, body)
                            }

                            override fun onLinkCopied(link: String) {
                                onLinkCopiedState.value(link)
                            }
                        }
                        viewRef = view
                        handle?.view = view
                        view
                    },
                    update = { view ->
                        view.cursorStyle = cursor
                        view.setFontSizeSp(fontSizeSp.toFloat())
                    },
                    onRelease = { view ->
                        view.host = null
                        view.release()
                        if (viewRef === view) viewRef = null
                        if (handle?.view === view) handle.view = null
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (failure == null && !ready) {
            LoadingState(label = "Starting terminal", modifier = Modifier.fillMaxSize())
        }
    }
}

private fun toCursorStyle(cursorStyle: String): TerminalView.CursorStyle = when (cursorStyle) {
    "bar" -> TerminalView.CursorStyle.BAR
    "underline" -> TerminalView.CursorStyle.UNDERLINE
    else -> TerminalView.CursorStyle.BLOCK
}

