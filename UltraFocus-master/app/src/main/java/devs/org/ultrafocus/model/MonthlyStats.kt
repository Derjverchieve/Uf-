package devs.org.ultrafocus.model

data class MonthlyStats(
    val year: Int,
    val month: Int,               // 1-based (January = 1)
    val label: String,            // e.g. "June 2025"
    val sessionCount: Int,
    val totalFocusedTimeMs: Long,
    val avgFocusScore: Double,
    val efm: Double,              // Effective Focus Minutes: Σ (score/100) × (focusMs/60000)
    val rank: Int                 // 1 = best month by EFM; 0 = placeholder before ranking
)
