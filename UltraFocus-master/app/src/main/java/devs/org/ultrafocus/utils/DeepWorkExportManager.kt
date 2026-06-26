package devs.org.ultrafocus.utils

import android.content.Context
import android.net.Uri
import devs.org.ultrafocus.database.AppDatabase
import devs.org.ultrafocus.model.FocusSession
import devs.org.ultrafocus.model.SessionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Exports Deep Work session history as CSV — one row per session, suitable
 * for opening in any spreadsheet app or charting tool.
 *
 * Mirrors BackupManager's export pattern: the caller supplies a Uri (from
 * an ActivityResultContracts.CreateDocument launcher) and we write straight
 * to it via ContentResolver, no FileProvider needed.
 */
object DeepWorkExportManager {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    fun exportSessionsCsv(context: Context, uri: Uri): Boolean {
        return try {
            val sessions: List<FocusSession> = runBlocking {
                AppDatabase.getDatabase(context).focusSessionDao().getAllSessions().first()
            }

            val csv = buildString {
                append("Date,Primary App,Status,Target (min),Wall Clock (min),Focused (min),Paused (min),Pause Count,Focus Score,Avg Focus Segment (min)\n")
                for (s in sessions.sortedByDescending { it.startTime }) {
                    val wallClockMin = millisToMinutes((s.endTime ?: s.startTime) - s.startTime)
                    val segments = (s.pauseCount + 1).coerceAtLeast(1)
                    val avgSegmentMin = millisToMinutes(s.focusedTimeMs / segments)

                    append(csvField(dateFormat.format(Date(s.startTime)))).append(',')
                    append(csvField(s.primaryAppName)).append(',')
                    append(csvField(s.status.name)).append(',')
                    append(millisToMinutes(s.targetDurationMs)).append(',')
                    append(wallClockMin).append(',')
                    append(millisToMinutes(s.focusedTimeMs)).append(',')
                    append(millisToMinutes(s.pauseTimeMs)).append(',')
                    append(s.pauseCount).append(',')
                    append(s.focusScore ?: "").append(',')
                    append(avgSegmentMin).append('\n')
                }
            }

            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(csv.toByteArray())
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun millisToMinutes(ms: Long): Long = TimeUnit.MILLISECONDS.toMinutes(ms)

    // Minimal CSV escaping — wraps in quotes and doubles any internal quotes,
    // only when the field actually needs it (contains a comma, quote, or newline).
    private fun csvField(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}
