package devs.org.ultrafocus.model

data class HourlyFocusStats(
    val hour: Int,            // 0–23
    val totalMinutes: Float,  // total focused minutes across all sessions starting this hour
    val avgScore: Double      // average focus score (0–100); 0 when no sessions
)
