package devs.org.ultrafocus.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import devs.org.ultrafocus.R
import devs.org.ultrafocus.model.FocusSession
import devs.org.ultrafocus.model.HistoryItem
import devs.org.ultrafocus.utils.DurationFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionHistoryAdapter(
    private var items: List<HistoryItem>,
    private val onClick: (FocusSession) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

    companion object {
        private const val TYPE_SESSION = 0
        private const val TYPE_DAY_SUMMARY = 1
    }

    // ── View holders ──────────────────────────────────────────────────────────

    class SessionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val app: TextView     = view.findViewById(R.id.txtHistoryApp)
        val date: TextView    = view.findViewById(R.id.txtHistoryDate)
        val focused: TextView = view.findViewById(R.id.txtHistoryFocused)
        val score: TextView   = view.findViewById(R.id.txtHistoryScore)
    }

    class DaySummaryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.txtDayLabel)
        val total: TextView = view.findViewById(R.id.txtDayTotal)
    }

    // ── Adapter overrides ────────────────────────────────────────────────────

    fun submitList(newItems: List<HistoryItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is HistoryItem.SessionRow    -> TYPE_SESSION
        is HistoryItem.DaySummaryRow -> TYPE_DAY_SUMMARY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_DAY_SUMMARY -> DaySummaryViewHolder(
                inflater.inflate(R.layout.day_summary_item, parent, false)
            )
            else -> SessionViewHolder(
                inflater.inflate(R.layout.session_history_item, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is HistoryItem.SessionRow -> {
                holder as SessionViewHolder
                val s = item.session
                holder.app.text     = s.primaryAppName
                holder.date.text    = dateFormat.format(Date(s.startTime))
                holder.focused.text = DurationFormatter.formatCompact(s.focusedTimeMs)
                holder.score.text   = (s.focusScore ?: 0).toString()
                holder.itemView.setOnClickListener { onClick(s) }
            }
            is HistoryItem.DaySummaryRow -> {
                holder as DaySummaryViewHolder
                holder.label.text = item.dateLabel.uppercase()
                holder.total.text = buildString {
                    append(DurationFormatter.formatCompact(item.totalFocusedMs))
                    append("  ·  ")
                    append("${item.sessionCount} session${if (item.sessionCount != 1) "s" else ""}")
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
