package devs.org.ultrafocus.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import devs.org.ultrafocus.R
import devs.org.ultrafocus.model.MonthlyStats
import devs.org.ultrafocus.utils.DurationFormatter
import java.util.Locale

class MonthlyStatsAdapter(private var items: List<MonthlyStats>) :
    RecyclerView.Adapter<MonthlyStatsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rank: TextView = view.findViewById(R.id.txtMonthRank)
        val label: TextView = view.findViewById(R.id.txtMonthLabel)
        val efm: TextView = view.findViewById(R.id.txtMonthEfm)
        val focusTime: TextView = view.findViewById(R.id.txtMonthFocusTime)
        val sessions: TextView = view.findViewById(R.id.txtMonthSessions)
        val avgScore: TextView = view.findViewById(R.id.txtMonthAvgScore)
    }

    fun submitList(newItems: List<MonthlyStats>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.monthly_stats_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context

        holder.rank.text = "#${item.rank}"
        holder.label.text = item.label
        holder.efm.text = String.format(Locale.US, "%.1f efm", item.efm)
        holder.focusTime.text = DurationFormatter.formatCompact(item.totalFocusedTimeMs)
        holder.sessions.text = "${item.sessionCount} session${if (item.sessionCount != 1) "s" else ""}"
        holder.avgScore.text = "avg ${item.avgFocusScore.toInt()}"

        // Colour rank number: gold/blue/amber for top 3, muted for the rest
        val rankColor = when (item.rank) {
            1 -> ctx.getColor(R.color.unlocked_green)
            2 -> ctx.getColor(R.color.primary)
            3 -> ctx.getColor(R.color.soft_block_amber)
            else -> ctx.getColor(R.color.text_secondary)
        }
        holder.rank.setTextColor(rankColor)
    }

    override fun getItemCount(): Int = items.size
}
