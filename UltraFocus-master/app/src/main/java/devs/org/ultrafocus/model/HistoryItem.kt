package devs.org.ultrafocus.model

/**
 * Union type for the session history RecyclerView.
 * The list is ordered: [SessionRow (newest first), ..., DaySummaryRow] per day.
 */
sealed class HistoryItem {
    data class SessionRow(val session: FocusSession) : HistoryItem()
    data class DaySummaryRow(
        val dateLabel: String,       // "Today", "Yesterday", or "Mon, Jun 23"
        val totalFocusedMs: Long,
        val sessionCount: Int
    ) : HistoryItem()
}
