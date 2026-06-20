package devs.org.ultrafocus.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "PauseEvent", indices = [Index("sessionId")])
data class PauseEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val startTime: Long,
    val endTime: Long? = null,
    val durationMs: Long = 0,
    val reason: PauseReason,
    // The app the user switched to. Null for MANUAL pauses, where they
    // stayed inside the primary work app the whole time.
    val appPackage: String? = null,
    val appName: String? = null
)
