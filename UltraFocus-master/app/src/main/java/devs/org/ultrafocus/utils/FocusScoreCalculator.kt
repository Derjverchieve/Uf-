package devs.org.ultrafocus.utils

import kotlin.math.roundToInt

/**
 * focusScore = focusedTime / (focusedTime + pauseTime) * 100, clamped to 0..100.
 *
 * It's a RATE, not an absolute amount — a clean 10-minute session scores
 * 100, same as a clean 2-hour session. The score tells you how clean a
 * session was; total focus hours (tracked per-session and rolled up
 * elsewhere) tells you how much you actually got done. Neither number
 * alone is the full picture, which is why both are surfaced separately.
 *
 * Zero elapsed time (e.g. scoring mid-session before anything has
 * happened) returns 100 rather than dividing by zero — nothing has gone
 * wrong yet.
 */
object FocusScoreCalculator {
    fun calculate(focusedTimeMs: Long, pauseTimeMs: Long): Int {
        val total = focusedTimeMs + pauseTimeMs
        if (total <= 0L) return 100
        val raw = (focusedTimeMs.toDouble() / total.toDouble()) * 100.0
        return raw.roundToInt().coerceIn(0, 100)
    }
}
