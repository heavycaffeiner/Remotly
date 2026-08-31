package com.remotly.app.terminal

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sign

/**
 * Decelerating fling, in whole terminal rows.
 *
 * The terminal scrolls by rows, so a fling is stepped rather than tweened: the
 * curve is sampled each frame and the leftover pixels are carried, the same way
 * a drag does.
 *
 * The curve is the one Android's own scroller uses, so a flick here travels the
 * distance and lasts the time a flick anywhere else on the device does. Both
 * grow faster than the launch velocity: distance with its 1.74 power, duration
 * with its 0.34 power. Plain exponential decay, which this used before, makes
 * distance merely proportional to velocity and duration nearly constant, so a
 * gentle flick coasted far too long and a hard one refused to travel.
 *
 * Kept free of Android types so the arithmetic is unit testable.
 */
class ScrollFling {

    private var totalPx = 0f
    private var durationSec = 0f
    private var elapsedSec = 0f
    /** Curve position already turned into whole rows. */
    private var emittedPx = 0f
    private var direction = 1f
    private var running = false

    val isRunning: Boolean get() = running

    /**
     * Starts a fling. Velocity is in pixels per second, positive when the
     * content should move toward the scrollback.
     *
     * [pixelsPerInch] scales the friction to the screen, so the same flick
     * covers the same physical distance on any density.
     */
    fun start(velocityPxPerSec: Float, pixelsPerInch: Float = DEFAULT_PPI) {
        val v = abs(velocityPxPerSec)
        if (v < MIN_START_VELOCITY) {
            stop()
            return
        }
        val capped = v.coerceAtMost(MAX_VELOCITY)
        val friction = physicalFriction(pixelsPerInch)

        // Android's scroller solves the same two expressions for how far a
        // flick travels and how long it takes to stop.
        val ratio = (INFLEXION * capped / friction).toDouble()
        totalPx = (friction * ratio.pow(DISTANCE_EXPONENT)).toFloat()
        durationSec = ratio.pow(DURATION_EXPONENT).toFloat()

        if (totalPx < 1f || durationSec <= 0f) {
            stop()
            return
        }
        direction = sign(velocityPxPerSec)
        elapsedSec = 0f
        emittedPx = 0f
        running = true
    }

    fun stop() {
        running = false
        totalPx = 0f
        durationSec = 0f
        elapsedSec = 0f
        emittedPx = 0f
    }

    /**
     * Advances by one frame and returns the rows to scroll.
     *
     * Returns zero both when the fling has finished and when this frame did
     * not accumulate a whole row; [isRunning] distinguishes them.
     */
    fun advance(frameSeconds: Float, cellHeightPx: Int): Int {
        if (!running || cellHeightPx <= 0 || frameSeconds <= 0f) return 0

        elapsedSec += frameSeconds
        val t = (elapsedSec / durationSec).coerceIn(0f, 1f)
        // Viscous easing, as used by the platform scroller: fast at release,
        // asymptotically slow at the end.
        val eased = 1f - exp(-VISCOUS_RATE * t)
        val target = totalPx * (eased / VISCOUS_NORMALIZER)

        if (t >= 1f) running = false

        // Emit whole rows against the curve's own position rather than a
        // per-frame delta, so travel below one row per frame accumulates
        // instead of truncating to zero every time.
        val pending = target - emittedPx
        val rows = (pending / cellHeightPx).toInt()
        if (rows == 0) return 0
        emittedPx += rows * cellHeightPx
        return (rows * direction).toInt()
    }

    private companion object {
        /** Below this a flick is a tap or a slow drag release, not a fling. */
        const val MIN_START_VELOCITY = 80f

        /** Clamp, so a very fast flick cannot skip the whole scrollback. */
        const val MAX_VELOCITY = 12000f

        /** Typical phone density, used when the caller does not supply one. */
        const val DEFAULT_PPI = 440f

        /** Where the platform curve switches from deceleration to drag. */
        const val INFLEXION = 0.35

        /** Platform scroll friction, tuned against a physical surface. */
        const val FRICTION = 0.015

        /**
         * Fixed by the platform's 0.78-per-0.9 decay: distance grows with
         * velocity to the 1.74, duration with velocity to the 0.34.
         */
        val DECELERATION: Double = ln(0.78) / ln(0.9)
        val DISTANCE_EXPONENT: Double = DECELERATION / (DECELERATION - 1.0)
        val DURATION_EXPONENT: Double = 1.0 / (DECELERATION - 1.0)

        /** Gravity in inches per second squared, times the platform's 0.84. */
        const val GRAVITY_INCHES = 9.80665 * 39.37 * 0.84

        const val VISCOUS_RATE = 4.5f

        /** exp() reaches 1 only at infinity; rescale so the fling completes. */
        val VISCOUS_NORMALIZER: Float = 1f - exp(-VISCOUS_RATE)

        fun physicalFriction(pixelsPerInch: Float): Float =
            (FRICTION * GRAVITY_INCHES * pixelsPerInch.coerceAtLeast(1f)).toFloat()
    }
}
