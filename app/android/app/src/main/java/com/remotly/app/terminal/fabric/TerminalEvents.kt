package com.remotly.app.terminal.fabric

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.events.Event

// Direct events for the Fabric terminal component. Each maps a TerminalView.Host
// callback to a codegen'd `DirectEventHandler` prop: the JS prop `onXxx` is
// delivered from the native event name `topXxx`. `canCoalesce` is false across
// the board because input/ptywrite/copy carry incremental or one-shot data that
// must never be dropped in flight.
//
// `target` (the view tag) is included on every payload, matching ReactTextInput's
// codegen'd events, so the handler can identify the source view.

internal class TerminalReadyEvent(
  surfaceId: Int,
  viewTag: Int,
  private val cols: Int,
  private val rows: Int,
) : Event<TerminalReadyEvent>(surfaceId, viewTag) {
  override fun getEventName(): String = NAME
  override fun canCoalesce(): Boolean = false
  override fun getEventData(): WritableMap =
    Arguments.createMap().apply {
      putInt("target", viewTag)
      putInt("cols", cols)
      putInt("rows", rows)
    }

  companion object {
    const val NAME = "topReady"
  }
}

internal class TerminalErrorEvent(
  surfaceId: Int,
  viewTag: Int,
  private val code: String,
) : Event<TerminalErrorEvent>(surfaceId, viewTag) {
  override fun getEventName(): String = NAME
  override fun canCoalesce(): Boolean = false
  override fun getEventData(): WritableMap =
    Arguments.createMap().apply {
      putInt("target", viewTag)
      putString("code", code)
    }

  companion object {
    const val NAME = "topError"
  }
}

internal class TerminalInputEvent(
  surfaceId: Int,
  viewTag: Int,
  private val dataB64: String,
) : Event<TerminalInputEvent>(surfaceId, viewTag) {
  override fun getEventName(): String = NAME
  override fun canCoalesce(): Boolean = false
  override fun getEventData(): WritableMap =
    Arguments.createMap().apply {
      putInt("target", viewTag)
      putString("data", dataB64)
    }

  companion object {
    const val NAME = "topInput"
  }
}

internal class TerminalResizeEvent(
  surfaceId: Int,
  viewTag: Int,
  private val cols: Int,
  private val rows: Int,
) : Event<TerminalResizeEvent>(surfaceId, viewTag) {
  override fun getEventName(): String = NAME
  override fun canCoalesce(): Boolean = false
  override fun getEventData(): WritableMap =
    Arguments.createMap().apply {
      putInt("target", viewTag)
      putInt("cols", cols)
      putInt("rows", rows)
    }

  companion object {
    const val NAME = "topResizeGrid"
  }
}

internal class TerminalBellEvent(surfaceId: Int, viewTag: Int) :
  Event<TerminalBellEvent>(surfaceId, viewTag) {
  override fun getEventName(): String = NAME
  override fun canCoalesce(): Boolean = false
  override fun getEventData(): WritableMap =
    Arguments.createMap().apply { putInt("target", viewTag) }

  companion object {
    const val NAME = "topBell"
  }
}

// Terminal focus changed. JS applies its keyboard policy from this rather than
// guessing: a view can hold focus with the keyboard hidden, so focus and
// keyboard visibility are separate facts.
internal class TerminalFocusEvent(
  surfaceId: Int,
  viewTag: Int,
  private val focused: Boolean,
) : Event<TerminalFocusEvent>(surfaceId, viewTag) {
  override fun getEventName(): String = NAME
  override fun canCoalesce(): Boolean = false
  override fun getEventData(): WritableMap =
    Arguments.createMap().apply {
      putInt("target", viewTag)
      putBoolean("focused", focused)
    }

  companion object {
    const val NAME = "topFocusChange"
  }
}

// A selection was made or dropped by touch, so the screen can offer Copy.
internal class TerminalSelectionEvent(
  surfaceId: Int,
  viewTag: Int,
  private val active: Boolean,
) : Event<TerminalSelectionEvent>(surfaceId, viewTag) {
  override fun getEventName(): String = NAME
  override fun canCoalesce(): Boolean = false
  override fun getEventData(): WritableMap =
    Arguments.createMap().apply {
      putInt("target", viewTag)
      putBoolean("active", active)
    }

  companion object {
    const val NAME = "topSelectionChange"
  }
}

// Paste was chosen from the selection toolbar. The clipboard is read on the JS
// side, which owns sending input to the session.
internal class TerminalPasteEvent(
  surfaceId: Int,
  viewTag: Int,
) : Event<TerminalPasteEvent>(surfaceId, viewTag) {
  override fun getEventName(): String = NAME
  override fun canCoalesce(): Boolean = false
  override fun getEventData(): WritableMap =
    Arguments.createMap().apply { putInt("target", viewTag) }

  companion object {
    const val NAME = "topPasteRequest"
  }
}

internal class TerminalTitleEvent(
  surfaceId: Int,
  viewTag: Int,
  private val title: String,
) : Event<TerminalTitleEvent>(surfaceId, viewTag) {
  override fun getEventName(): String = NAME
  override fun canCoalesce(): Boolean = false
  override fun getEventData(): WritableMap =
    Arguments.createMap().apply {
      putInt("target", viewTag)
      putString("title", title)
    }

  companion object {
    const val NAME = "topTitle"
  }
}

internal class TerminalFontSizeEvent(
  surfaceId: Int,
  viewTag: Int,
  private val fontSize: Int,
) : Event<TerminalFontSizeEvent>(surfaceId, viewTag) {
  override fun getEventName(): String = NAME
  override fun canCoalesce(): Boolean = false
  override fun getEventData(): WritableMap =
    Arguments.createMap().apply {
      putInt("target", viewTag)
      putInt("fontSize", fontSize)
    }

  companion object {
    const val NAME = "topFontSizeChange"
  }
}

internal class TerminalPtyWriteEvent(
  surfaceId: Int,
  viewTag: Int,
  private val dataB64: String,
) : Event<TerminalPtyWriteEvent>(surfaceId, viewTag) {
  override fun getEventName(): String = NAME
  override fun canCoalesce(): Boolean = false
  override fun getEventData(): WritableMap =
    Arguments.createMap().apply {
      putInt("target", viewTag)
      putString("data", dataB64)
    }

  companion object {
    const val NAME = "topPtyWrite"
  }
}

internal class TerminalCopyEvent(
  surfaceId: Int,
  viewTag: Int,
  private val ok: Boolean,
  private val data: String,
) : Event<TerminalCopyEvent>(surfaceId, viewTag) {
  override fun getEventName(): String = NAME
  override fun canCoalesce(): Boolean = false
  override fun getEventData(): WritableMap =
    Arguments.createMap().apply {
      putInt("target", viewTag)
      putBoolean("ok", ok)
      putString("data", data)
    }

  companion object {
    const val NAME = "topCopy"
  }
}
