package com.remotly.app.ui.terminal

import android.os.Handler
import android.os.Looper

/** Delay from press to the first repeat, when none is configured. */
const val REPEAT_DELAY_MS = 400

/** Interval between repeats once the stream has started. */
const val REPEAT_INTERVAL_MS = 50

/** Bounds for a user-chosen delay. */
const val MIN_REPEAT_DELAY_MS = 150
const val MAX_REPEAT_DELAY_MS = 1000

/** Repeats at the slow rate before the interval starts shortening. */
private const val ACCELERATE_AFTER = 6

/** The interval never drops below this, however long the key is held. */
private const val MIN_INTERVAL_MS = 20

/** Clamps a stored preference into the supported range. */
fun clampRepeatDelay(ms: Int): Int = ms.coerceIn(MIN_REPEAT_DELAY_MS, MAX_REPEAT_DELAY_MS)

/**
 * Delay before repeat number [count], where 1 is the first repeat.
 *
 * Returns [delayMs] for the first, then the steady interval, which shortens
 * once the key has clearly been held rather than tapped.
 */
fun repeatDelayMs(count: Int, delayMs: Int = REPEAT_DELAY_MS): Int {
    if (count <= 1) return clampRepeatDelay(delayMs)
    if (count <= ACCELERATE_AFTER) return REPEAT_INTERVAL_MS
    return maxOf(MIN_INTERVAL_MS, REPEAT_INTERVAL_MS / 2)
}

/**
 * Auto-repeat timing for a held key.
 *
 * A held arrow should behave like a held key on a physical keyboard: one
 * press straight away, a pause long enough that an ordinary tap never
 * repeats, then a steady stream. The stream accelerates to a floor, because
 * moving a cursor across a long line one slow tick at a time is worse than
 * useless.
 *
 * One repeater serves a whole key row rather than one per key. Only one key
 * can be held at a time, and a per-key repeater cannot enforce that: a finger
 * that slides from one key to the next, or a scroll that steals the touch,
 * leaves the first key's release unfired and two streams running at once.
 *
 * [schedule] and [cancelTimer] are seams for tests; the default posts to the
 * main-thread handler the way the real repeat stream runs.
 */
class KeyRepeater(
    private val fire: (String) -> Unit,
    private val schedule: (Long, () -> Unit) -> Any = { ms, action ->
        val runnable = Runnable(action)
        mainHandler.postDelayed(runnable, ms)
        runnable
    },
    private val cancelTimer: (Any) -> Unit = { token ->
        if (token is Runnable) mainHandler.removeCallbacks(token)
    },
    initialDelayMs: Int = REPEAT_DELAY_MS,
) {
    private var delayMs: Int = clampRepeatDelay(initialDelayMs)
    private var timer: Any? = null
    private var count = 0
    private var heldKeyValue: String? = null

    /** The key being held, or null. */
    val heldKey: String? get() = heldKeyValue

    /** Changes the first-repeat delay. Takes effect on the next press. */
    fun setDelay(ms: Int) {
        delayMs = clampRepeatDelay(ms)
    }

    /**
     * Presses [key] once and arms the repeat.
     *
     * Any key already held is released first, so a finger sliding from one
     * key to another cannot leave two streams running.
     */
    fun press(key: String) {
        stop()
        heldKeyValue = key
        fire(key)
        count = 0
        arm()
    }

    /**
     * Releases [key].
     *
     * A key that is not the one being held is ignored: a stale release
     * arriving after the next key is already down would otherwise kill the
     * live stream. Omit the argument to release whatever is held.
     */
    fun release(key: String? = null) {
        if (key != null && heldKeyValue != key) return
        stop()
    }

    /** Releases whatever is held. Safe to call when nothing is. */
    fun stop() {
        timer?.let(cancelTimer)
        timer = null
        count = 0
        heldKeyValue = null
    }

    private fun arm() {
        count += 1
        val held = heldKeyValue
        val delay = repeatDelayMs(count, delayMs).toLong()
        timer = schedule(delay) {
            // The key can be released between the timer firing and this
            // running.
            if (held != null && heldKeyValue == held) {
                fire(held)
                arm()
            }
        }
    }

    private companion object {
        val mainHandler = Handler(Looper.getMainLooper())
    }
}
