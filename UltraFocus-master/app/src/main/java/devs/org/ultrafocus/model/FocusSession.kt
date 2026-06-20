package devs.org.ultrafocus.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "FocusSession")
data class FocusSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val primaryAppPackage: String,
    val primaryAppName: String,
    val targetDurationMs: Long,
    val startTime: Long,
    val endTime: Long? = null,
    val focusedTimeMs: Long = 0,
    val pauseTimeMs: Long = 0,
    val pauseCount: Int = 0,
    val focusScore: Int? = null,
    val status: SessionStatus = SessionStatus.RUNNING,
    // Bumped on every persisted checkpoint (a real transition, or the ~30s
    // ticker checkpoint while running). If the process gets killed mid
    // session, we use this timestamp to close the session out honestly on
    // next launch — using only what was actually saved rather than
    // guessing what happened while the app was dead. See
    // DeepWorkSessionManager.recoverOrphanedSession().
    val updatedAt: Long = startTime
)
