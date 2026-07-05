package devs.org.ultrafocus.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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

    /**
     * Machine-readable backup export — raw millisecond timestamps and all DB fields.
     * This format is what DeepWorkImportManager reads back. Not for spreadsheets;
     * use exportSessionsCsv for human-readable analysis.
     */
    private fun millisToMinutes(ms: Long): Long = TimeUnit.MILLISECONDS.toMinutes(ms)

    fun exportBackupCsv(context: Context, uri: Uri): Boolean {
        return try {
            val csv = runBlocking { buildBackupCsvString(context) }
            context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
            true
        } catch (_: Exception) { false }
    }

    /**
     * Silently writes the full backup CSV to the public Downloads folder via
     * MediaStore — no file picker, no permission prompt, on API 29+. Always
     * overwrites the SAME filename rather than creating a new dated file
     * every time, so there's exactly one place to always find the latest
     * complete backup instead of accumulating a new near-duplicate file
     * after every single session. Called automatically whenever a session
     * or a whole cycle plan finishes — see DeepWorkSessionManager.
     *
     * On API < 29, falls back to the app's own external files directory —
     * no permission needed there at any API level, but it's a less obvious
     * location (Android/data/devs.org.ultrafocus/files/), since writing to
     * the shared Downloads folder pre-Q requires WRITE_EXTERNAL_STORAGE,
     * which isn't worth adding just for this.
     *
     * Deliberately silent on failure — this is a convenience side-effect
     * riding on the back of a session ending, not something that should
     * ever interrupt or announce itself unless it fails, and even then only
     * via the caller's own toast, not from in here.
     */
    suspend fun autoExportBackup(context: Context): Boolean {
        return try {
            val csv = buildBackupCsvString(context)
            val displayName = "ultrafocus_autobackup.csv"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = findOrCreateDownloadUri(context, displayName) ?: return false
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(csv.toByteArray()) }
                    ?: return false
            } else {
                val dir = context.getExternalFilesDir(null) ?: return false
                java.io.File(dir, displayName).writeText(csv)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun findOrCreateDownloadUri(context: Context, displayName: String): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        resolver.query(collection, projection, selection, arrayOf(displayName), null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                return ContentUris.withAppendedId(collection, id)
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
        }
        return resolver.insert(collection, values)
    }

    // Shared by exportBackupCsv (manual, via file picker) and
    // autoExportBackup (silent, via MediaStore) — single source of truth
    // for the backup CSV's format, so the two paths can never drift apart.
    private suspend fun buildBackupCsvString(context: Context): String {
        val sessions: List<FocusSession> =
            AppDatabase.getDatabase(context).focusSessionDao().getAllSessions().first()
        return buildString {
            append("id,primaryAppPackage,primaryAppName,targetDurationMs,startTime,")
            append("endTime,focusedTimeMs,pauseTimeMs,pauseCount,focusScore,status,updatedAt\n")
            for (s in sessions.sortedBy { it.startTime }) {
                append(s.id).append(',')
                append(csvField(s.primaryAppPackage)).append(',')
                append(csvField(s.primaryAppName)).append(',')
                append(s.targetDurationMs).append(',')
                append(s.startTime).append(',')
                append(s.endTime ?: "").append(',')
                append(s.focusedTimeMs).append(',')
                append(s.pauseTimeMs).append(',')
                append(s.pauseCount).append(',')
                append(s.focusScore ?: "").append(',')
                append(s.status.name).append(',')
                append(s.updatedAt).append('\n')
            }
        }
    }

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
