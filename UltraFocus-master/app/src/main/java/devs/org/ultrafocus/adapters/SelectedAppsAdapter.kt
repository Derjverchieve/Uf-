package devs.org.ultrafocus.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import devs.org.ultrafocus.databinding.SelectedAppItemBinding
import devs.org.ultrafocus.model.AppInfo
import devs.org.ultrafocus.utils.StrictModeManager

class SelectedAppsAdapter(
    private val context: Context,
    private val list: MutableList<AppInfo>
) : RecyclerView.Adapter<SelectedAppsAdapter.ViewHolder>() {

    private var onItemRemovedCallback: ((AppInfo) -> Unit)? = null
    private var onSetTimePeriodCallback: ((AppInfo) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = SelectedAppItemBinding.inflate(LayoutInflater.from(context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val appInfo = list[position]
        holder.bind(appInfo)
    }

    override fun getItemCount(): Int {
        return list.size
    }

    inner class ViewHolder(val binding: SelectedAppItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(appInfo: AppInfo) {
            try {
                Glide.with(context).load(appInfo.icon).into(binding.icon)
            } catch (e: Exception) {
                // Use default icon if app icon can't be loaded
            }

            binding.label.text = appInfo.appName

            // Display schedule if available
            val schedule = appInfo.fromTime ?: ""
            if (schedule.isNotEmpty()) {
                binding.timePeriodInfo.text = "Schedule: $schedule"
                binding.timePeriodInfo.visibility = ViewGroup.VISIBLE
            } else {
                binding.timePeriodInfo.visibility = ViewGroup.GONE
            }

            binding.delete.setOnClickListener {
                if (StrictModeManager.isLocked(context)) {
                    Toast.makeText(context, "Strict Mode Locked! Cannot remove apps.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < list.size) {
                    val removedApp = list[pos]
                    list.removeAt(pos)
                    notifyItemRemoved(pos)
                    onItemRemovedCallback?.invoke(removedApp)
                }
            }

            binding.btnSetTimePeriod.setOnClickListener {
                if (StrictModeManager.isLocked(context)) {
                    Toast.makeText(context, "Strict Mode Locked! Cannot modify schedules.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                onSetTimePeriodCallback?.invoke(appInfo)
            }
        }
    }

    fun setOnItemRemovedListener(callback: (AppInfo) -> Unit) {
        this.onItemRemovedCallback = callback
    }

    fun setOnSetTimePeriodListener(callback: (AppInfo) -> Unit) {
        this.onSetTimePeriodCallback = callback
    }
}