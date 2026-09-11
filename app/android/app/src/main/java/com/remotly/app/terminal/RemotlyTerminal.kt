package com.remotly.app.terminal

/**
 * JNI binding to libremotly_terminal.so (libghostty-vt, pinned in
 * app/android/ghostty/PIN.txt). All native calls must be made from the main
 * thread; the library does no internal locking for the caller.
 *
 * Data flow:
 *  - [nativeWrite] pushes SSH session output bytes into the terminal.
 *  - [nativeSendText]/[nativeSendKey]/[nativePasteText] encode user input and
 *    report it to [Listener.onInput] so the app can forward it to the session.
 *  - Effects (bell/title/terminal-initiated PTY writes) arrive via the listener.
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

    /**
     * A desktop notification the running program asked for.
     *
     * OSC 9 carries a body only, OSC 777 carries both; the title is empty for
     * the former. Neither is trusted for length: the strings come from the
     * remote and are bounded before they reach a notification.
     */
    fun onNotify(title: String, body: String)

    /**
     * A clipboard write the running program asked for, via OSC 52 or iTerm2's
     * OSC 1337 Copy.
     */
    fun onClipboardWrite(text: String)
  }

  external fun nativeCreate(
    cols: Int, rows: Int, scrollbackMaxBytes: Long,
    listener: Listener,
  ): Long

  /**
   * Points an existing terminal at a new listener.
   *
   * A terminal retained across screens holds a reference to the view that
   * created it, which the pane has already dropped by the time it is adopted.
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
   * Sends pasted text, wrapped for bracketed paste when the application asked
   * for it.
   *
   * Separate from [nativeSendText] because a paste is a block of text rather
   * than a run of keystrokes. Encoding it per keystroke turns every newline
   * into Enter, so a multi-line paste runs each line as a command.
   */
  external fun nativePasteText(handle: Long, text: String)

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

  /**
   * Visible Kitty graphics placements, or null when the screen holds none.
   *
   * Flat, twelve ints per placement: imageId, generation, viewportCol,
   * viewportRow, gridCols, gridRows, pixelWidth, pixelHeight, sourceX,
   * sourceY, sourceWidth, sourceHeight. Geometry only, so a placement that
   * merely scrolled costs no pixel copy.
   */
  external fun nativePlacements(handle: Long): IntArray?

  /**
   * An image's decoded pixels as [width, height, ARGB...], or null when the
   * id is unknown or its payload has not arrived yet.
   */
  external fun nativeImagePixels(handle: Long, imageId: Int): IntArray?

  /**
   * The link under a viewport cell, or null when there is none.
   *
   * An OSC 8 hyperlink is preferred; failing that the row is scanned for a
   * bare URL, so a program that never emitted OSC 8 still yields one.
   */
  external fun nativeLinkAt(handle: Long, col: Int, row: Int): String?
}
