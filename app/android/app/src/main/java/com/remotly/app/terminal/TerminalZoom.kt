package com.remotly.app.terminal

/** Pure font-size rules shared by the pinch gesture and its unit tests. */
object TerminalZoom {
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
