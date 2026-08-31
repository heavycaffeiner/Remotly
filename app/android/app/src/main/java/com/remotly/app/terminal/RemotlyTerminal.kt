package com.remotly.app.terminal

/**
 * JNI binding to libremotly_terminal.so (libghostty-vt, pinned in
 * app/android/ghostty/PIN.txt). All native calls must be made from the main
 * thread; the library does no internal locking for the caller.
 *
 * Data flow:
 *  - [feed] pushes daemon output bytes into the terminal.
 *  - [sendText]/[sendKey] encode user input and report it to [listener.onInput]
 *    so the app can forward it to the daemon.
 *  - Effects (bell/title/terminal-initiated PTY writes) arrive via [listener].
 */
object RemotlyTerminal {
  private val libraryError: String? = try {
    System.loadLibrary("remotly_terminal")
    null
  } catch (_: LinkageError) {
    // Linker errors can include sensitive device paths; expose only a stable
    // code through the view boundary.
    "library_load_failed"
  }

  fun isAvailable(): Boolean = libraryError == null

  fun unavailableCode(): String = libraryError ?: "renderer_failed"

  /** Callbacks delivered from native on the main thread. */
  interface Listener {
    fun onBell()
    fun onTitle(titleUtf8: ByteArray)
    /** User input (committed text and encoded key events), ready to send. */
    fun onInput(data: ByteArray)
    /** Terminal-initiated PTY writes (e.g. query responses), ready to send. */
    fun onPtyWrite(data: ByteArray)
  }

  external fun nativeCreate(
    cols: Int, rows: Int, scrollbackMaxBytes: Long,
    listener: Listener,
  ): Long

  /**
   * Points an existing terminal at a new listener.
   *
   * A terminal retained across screens holds a reference to the view that
   * created it, which React has already dropped by the time it is adopted.
   */
  external fun nativeRebind(handle: Long, listener: Listener)

  external fun nativeDestroy(handle: Long)
  external fun nativeWrite(handle: Long, data: ByteArray)
  external fun nativeResize(handle: Long, cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int)
  external fun nativeCursorX(handle: Long): Int
  external fun nativeCursorY(handle: Long): Int
  external fun nativeCols(handle: Long): Int
  external fun nativeRows(handle: Long): Int
  external fun nativeTotalRows(handle: Long): Int
  external fun nativeTitle(handle: Long): ByteArray?
  external fun nativeSendText(handle: Long, text: String)

  /**
   * Encodes a mouse event and writes it to the pty.
   *
   * Returns true when the application had mouse reporting on and the event
   * produced bytes. False means it wanted nothing, and the gesture belongs to
   * the terminal view instead.
   *
   * [action] is 0 press, 1 release, 2 motion. [button] is -1 for none.
   */
  /**
   * True when the running application asked for mouse reports.
   *
   * Read before a touch is known to be a tap or a scroll, so the view can
   * decide whether a drag becomes wheel reports without writing anything to
   * the pty.
   */
  external fun nativeMouseReporting(handle: Long): Boolean

  external fun nativeSendMouse(
    handle: Long,
    action: Int,
    button: Int,
    mods: Int,
    col: Int,
    row: Int,
    cellWidthPx: Int,
    cellHeightPx: Int,
  ): Boolean
  external fun nativeSendKey(
    handle: Long, ghosttyKey: Int, ghosttyMods: Int, utf8: String?, composing: Boolean,
  )
  /** Scrolls the viewport by whole rows. Negative moves into the scrollback. */
  external fun nativeScrollViewport(handle: Long, deltaRows: Int)

  /** Pins the viewport back to the active area. */
  external fun nativeScrollToBottom(handle: Long)

  /** Scrollbar geometry as [total, offset, len] in rows, or null. */
  external fun nativeScrollbar(handle: Long): LongArray?

  /**
   * Selects between two viewport cells, inclusive. Returns false when either
   * end is outside the grid.
   */
  external fun nativeSelectRange(
    handle: Long, startX: Int, startY: Int, endX: Int, endY: Int,
    rectangle: Boolean,
  ): Boolean

  /**
   * Word bounds under a viewport cell as [startX, startY, endX, endY], or null
   * when the cell holds nothing selectable.
   */
  external fun nativeSelectWord(handle: Long, col: Int, row: Int): IntArray?

  external fun nativeClearSelection(handle: Long)

  external fun nativeSelectAll(handle: Long)
  external fun nativeGetSelectionText(handle: Long): ByteArray?

  /**
   * Serializes the current frame into [dst], which must be a direct buffer.
   *
   * Returns the byte count written, 0 when no frame was produced, or the
   * negated required capacity when [dst] is too small. The caller keeps one
   * buffer across frames so a draw costs no allocation.
   */
  external fun nativeGetFrame(handle: Long, dst: java.nio.ByteBuffer): Int
}
