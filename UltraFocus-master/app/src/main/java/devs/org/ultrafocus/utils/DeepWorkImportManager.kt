package devs.org.ultrafocus.utils

import android.content.Context
import android.net.Uri
import devs.org.ultrafocus.database.AppDatabase
import devs.org.ultrafocus.model.FocusSession
import devs.org.ultrafocus.model.SessionStatus

/**
 * Imports sessions from a backup CSV produced by DeepWorkExportManager.exportBackupCsv.
 * The format is a raw dump of FocusSession fields (millisecond timestamps, enum names).
 * Duplicate detection uses startTime — any session whose startTime already exists in
 * the database is skipped without error.
 */
object DeepWorkImportManager {

    data class ImportResult(
        val imported: Int,
        val skipped: Int,   // already present (same startTime)
        val invalid: Int    // malformed rows
    )

    suspend fun importBackupCsv(context: Context, uri: Uri): ImportResult {
        val dao = AppDatabase.getDatabase(context).focusSessionDao()
        var imported = 0; var skipped = 0; var invalid = 0

        try {
            val lines = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.readLines()
                ?: return ImportResult(0, 0, 1)

            if (lines.size < 2) return ImportResult(0, 0, 0)

            // Build a HashSet of existing startTimes for O(1) dedup
            val existingStartTimes = dao.getAllStartTimes().toHashSet()

            for (line in lines.drop(1)) {       // drop header
                if (line.isBlank()) continue
                try {
                    val session = parseLine(line) ?: run { invalid++; continue }
                    if (session.startTime in existingStartTimes) { skipped++; continue }
                    dao.insert(session)
                    existingStartTimes.add(session.startTime)
                    imported++
                } catch (_: Exception) {
                    invalid++
                }
            }
        } catch (_: Exception) {
            invalid++
        }

        return ImportResult(imported, skipped, invalid)
    }

    private fun parseLine(line: String): FocusSession? {
        val cols = splitCsvLine(line)
        if (cols.size < 12) return null
        return FocusSession(
            id                = 0L,               // Room auto-assigns on insert
            primaryAppPackage = cols[1].unquote(),
            primaryAppName    = cols[2].unquote(),
            targetDurationMs  = cols[3].trim().toLong(),
            startTime         = cols[4].trim().toLong(),
            endTime           = cols[5].trim().toLongOrNull(),
            focusedTimeMs     = cols[6].trim().toLong(),
            pauseTimeMs       = cols[7].trim().toLong(),
            pauseCount        = cols[8].trim().toInt(),
            focusScore        = cols[9].trim().toIntOrNull(),
            status            = SessionStatus.valueOf(cols[10].trim()),
            updatedAt         = cols[11].trim().toLong()
        )
    }

    /** Minimal RFC 4180-compliant CSV splitter — handles quoted fields with escaped quotes. */
    private fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && !inQuotes -> inQuotes = true
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++   // escaped quote inside quoted field
                }
                c == '"' && inQuotes -> inQuotes = false
                c == ',' && !inQuotes -> { result.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        result.add(sb.toString())
        return result
    }

    private fun String.unquote(): String = trim().removeSurrounding("\"")
}
