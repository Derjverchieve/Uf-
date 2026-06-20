package devs.org.ultrafocus.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import devs.org.ultrafocus.R
import devs.org.ultrafocus.model.FocusSession
import devs.org.ultrafocus.utils.DurationFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionHistoryAdapter(
    private var items: List<FocusSession>,
    private val onClick: (FocusSession) -> Unit
) : RecyclerView.Adapter<SessionHistoryAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val app: TextView = view.findViewById(R.id.txtHistoryApp)
        val date: TextView = view.findViewById(R.id.txtHistoryDate)
        val focused: TextView = view.findViewById(R.id.txtHistoryFocused)
        val score: TextView = view.findViewById(R.id.txtHistoryScore)
    }

    fun submitList(newItems: List<FocusSession>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.session_history_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val session = items[position]
        holder.app.text = session.primaryAppName
        holder.date.text = dateFormat.format(Date(session.startTime))
        holder.focused.text = DurationFormatter.formatCompact(session.focusedTimeMs)
        holder.score.text = (session.focusScore ?: 0).toString()
        holder.itemView.setOnClickListener { onClick(session) }
    }

    override fun getItemCount(): Int = items.size
}
