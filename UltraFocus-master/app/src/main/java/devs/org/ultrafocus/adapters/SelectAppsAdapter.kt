package devs.org.ultrafocus.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import devs.org.ultrafocus.databinding.SelectAppsItemBinding
import devs.org.ultrafocus.model.AppInfo
import devs.org.ultrafocus.utils.StrictModeManager

class SelectAppsAdapter(
    private val context: Context,
    private val fullList: List<AppInfo>,
    private val initiallySelected: List<AppInfo> = emptyList()
) : RecyclerView.Adapter<SelectAppsAdapter.ViewHolder>() {

    private var displayList = ArrayList<AppInfo>(fullList)
    private val selectedAppsList = mutableListOf<AppInfo>()
    private var onAppDeselected: ((AppInfo) -> Unit)? = null

    init {
        selectedAppsList.addAll(initiallySelected)
    }

    fun filter(query: String) {
        displayList.clear()
        if (query.isEmpty()) {
            displayList.addAll(fullList)
        } else {
            val lowerQuery = query.lowercase()
            displayList.addAll(fullList.filter {
                (it.appName ?: "").lowercase().contains(lowerQuery) ||
                        (it.packageName ?: "").lowercase().contains(lowerQuery)
            })
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = SelectAppsItemBinding.inflate(LayoutInflater.from(context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (position < displayList.size) {
            val appInfo = displayList[position]
            val isChecked = selectedAppsList.any { it.packageName == appInfo.packageName }
            holder.bind(appInfo, isChecked)
        }
    }

    override fun getItemCount(): Int {
        return displayList.size
    }

    inner class ViewHolder(val binding: SelectAppsItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(appInfo: AppInfo, isChecked: Boolean) {
            try {
                // Safety check for icon
                if (appInfo.icon != null) {
                    Glide.with(context).load(appInfo.icon).into(binding.icon)
                } else {
                    binding.icon.setImageResource(android.R.drawable.sym_def_app_icon)
                }

                binding.label.text = appInfo.appName ?: "Unknown App"
                binding.checkbox.setOnCheckedChangeListener(null)
                binding.checkbox.isChecked = isChecked

                binding.checkbox.setOnClickListener {
                    if (StrictModeManager.isLocked(context)) {
                        binding.checkbox.isChecked = !binding.checkbox.isChecked
                        Toast.makeText(context, "Strict Mode Locked!", Toast.LENGTH_SHORT).show()
                    } else {
                        val nowChecked = binding.checkbox.isChecked
                        if (nowChecked) {
                            // Check if already added to avoid duplicates
                            if (!selectedAppsList.any { it.packageName == appInfo.packageName }) {
                                selectedAppsList.add(appInfo)
                            }
                        } else {
                            selectedAppsList.removeAll { it.packageName == appInfo.packageName }
                            onAppDeselected?.invoke(appInfo)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun onItemSelected(callback: (MutableList<AppInfo>) -> Unit){
        callback(selectedAppsList)
    }

    fun setOnAppDeselectedListener(callback: (AppInfo) -> Unit) {
        this.onAppDeselected = callback
    }
}