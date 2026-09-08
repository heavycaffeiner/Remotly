package com.remotly.app.terminal

/** Pure font-size rules shared by the pinch gesture and its unit tests. */
object TerminalZoom {
  /**
   * What a two-finger gesture turned out to be.
   *
   * Decided once and then kept: fingers dragged together drift apart as they
   * go, so a running total of span change crosses any threshold eventually and
   * a swipe ends up zooming halfway through.
   */
  enum class TwoFinger {
    UNDECIDED,
    PINCH,
    SWIPE,
  }

  /**
   * Travel, on either measure, before the gesture is called, and how far one
   * measure has to lead the other.
   *
   * A matched pair with `TWO_FINGER_SLOP_PX` and `TWO_FINGER_BIAS` in
   * features/terminal/muxGestures.ts, which decides the same gesture for the
   * swipe. Nothing shares a constant across the bridge, so both are written to
   * the same numbers on purpose: read differently, one side zooms while the
   * other moves the workspace.
   */
  const val DECIDE_SLOP_DP = 16f
  const val DECIDE_BIAS = 1.5f

  /**
   * Which gesture two fingers are making.
   *
   * A pinch changes the distance between the fingers and barely moves the
   * point between them; a swipe moves that point and holds the distance. Both
   * are measured from where the gesture began, and neither counts until it
   * clearly leads the other, so an unclear gesture stays undecided and does
   * nothing rather than zooming by accident.
   */
  fun classify(
    spanChangePx: Float,
    focalTravelPx: Float,
    slopPx: Float,
  ): TwoFinger {
    val span = kotlin.math.abs(spanChangePx)
    val travel = kotlin.math.abs(focalTravelPx)
    if (travel > slopPx && travel > span * DECIDE_BIAS) return TwoFinger.SWIPE
    if (span > slopPx && span > travel * DECIDE_BIAS) return TwoFinger.PINCH
    return TwoFinger.UNDECIDED
  }

  const val MIN_SP = 8f
  const val MAX_SP = 32f

  fun scale(currentSp: Float, factor: Float): Float {
    if (!factor.isFinite() || factor <= 0f) return currentSp.coerceIn(MIN_SP, MAX_SP)
    return (currentSp * factor).coerceIn(MIN_SP, MAX_SP)
  }

  fun settle(currentSp: Float): Int =
    currentSp.coerceIn(MIN_SP, MAX_SP).toInt().let { floor ->
      if (currentSp - floor >= 0.5f) floor + 1 else floor
    }.coerceIn(MIN_SP.toInt(), MAX_SP.toInt())
}
