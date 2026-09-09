package com.remotly.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalZoomTest {
  @Test fun scalesIncrementally() {
    assertEquals(21f, TerminalZoom.scale(14f, 1.5f), 0.001f)
  }

  @Test fun clampsToReadableBounds() {
    assertEquals(8f, TerminalZoom.scale(8f, 0.1f), 0.001f)
    assertEquals(32f, TerminalZoom.scale(32f, 4f), 0.001f)
  }

  @Test fun ignoresInvalidFactorsAndSettlesToWholeSp() {
    assertEquals(14f, TerminalZoom.scale(14f, Float.NaN), 0.001f)
    assertEquals(15, TerminalZoom.settle(14.6f))
    assertEquals(14, TerminalZoom.settle(14.4f))
  }
}
