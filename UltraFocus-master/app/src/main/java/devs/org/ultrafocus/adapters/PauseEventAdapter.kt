package devs.org.ultrafocus.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import devs.org.ultrafocus.R
import devs.org.ultrafocus.utils.DurationFormatter

/** A simple row: a label + a duration. Used for both the live pause log
 *  (one row per PauseEvent) and the summary's per-app breakdown
 *  (one row per AppBreakItem) — both just need "label, how long". */
data class PauseRow(val label: String, val durationMs: Long, val ongoing: Boolean = false)

class PauseEventAdapter(private var items: List<PauseRow>) :
    RecyclerView.Adapter<PauseEventAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.txtPauseLabel)
        val duration: TextView = view.findViewById(R.id.txtPauseDuration)
    }

    fun submitList(newItems: List<PauseRow>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.pause_event_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.label.text = item.label
        holder.duration.text = if (item.ongoing) "ongoing" else DurationFormatter.formatCompact(item.durationMs)
    }

    override fun getItemCount(): Int = items.size
}
