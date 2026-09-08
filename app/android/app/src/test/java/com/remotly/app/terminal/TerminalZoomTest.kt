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

  // The reported bug: two fingers dragged across the terminal changed the
  // font size, because they drift apart as they travel and the drift adds up.
  @Test fun readsFingersMovingTogetherAsASwipe() {
    // 300px of travel with 30px of drift.
    assertEquals(
      TerminalZoom.TwoFinger.SWIPE,
      TerminalZoom.classify(30f, 300f, 40f),
    )
  }

  @Test fun readsFingersMovingApartAsAPinch() {
    // 400px of spread, and the point between them barely moved.
    assertEquals(
      TerminalZoom.TwoFinger.PINCH,
      TerminalZoom.classify(400f, 20f, 40f),
    )
  }

  // Neither one leading is the case that used to zoom by accident. It waits
  // instead, and the gesture does nothing until one of them leads.
  @Test fun waitsWhileNeitherMeasureLeads() {
    assertEquals(
      TerminalZoom.TwoFinger.UNDECIDED,
      TerminalZoom.classify(120f, 100f, 40f),
    )
    assertEquals(
      TerminalZoom.TwoFinger.UNDECIDED,
      TerminalZoom.classify(10f, 12f, 40f),
    )
  }

  // A pinch closing the fingers reports a negative span change.
  @Test fun readsAPinchInEitherDirection() {
    assertEquals(
      TerminalZoom.TwoFinger.PINCH,
      TerminalZoom.classify(-400f, 20f, 40f),
    )
  }
}
