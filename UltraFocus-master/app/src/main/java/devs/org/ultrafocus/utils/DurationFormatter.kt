package devs.org.ultrafocus.utils

import java.util.Locale

object DurationFormatter {

    /** "1h 23m", "23m 04s", "0:45" depending on magnitude — for live timer display. */
    fun formatClock(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** "1h 23m 04s", "23m 04s", "45s" — for summaries/history, now with second-level precision. */
    fun formatCompact(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return buildString {
            if (hours > 0) append("${hours}h ")
            if (hours > 0 || minutes > 0) append("${minutes}m ")
            append("${seconds}s")
        }
    }
}
