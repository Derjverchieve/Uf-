package devs.org.ultrafocus.adapters

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import devs.org.ultrafocus.databinding.SelectedAppItemBinding
import devs.org.ultrafocus.model.AppInfo
import devs.org.ultrafocus.utils.ItemStrictModeManager
import devs.org.ultrafocus.utils.TypeToAccessDialog

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
        holder.bind(list[position])
    }

    override fun getItemCount() = list.size

    inner class ViewHolder(val binding: SelectedAppItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(appInfo: AppInfo) {
            try {
                Glide.with(context).load(appInfo.icon).into(binding.icon)
            } catch (_: Exception) {}

            // App name — show lock icon if strictly locked
            val isStrictLocked = ItemStrictModeManager.isLocked(context, appInfo.packageName)
            val isStrictEnabled = ItemStrictModeManager.isEnabled(context, appInfo.packageName)
            binding.label.text = when {
                isStrictLocked  -> "${appInfo.appName} 🔒"
                isStrictEnabled -> "${appInfo.appName} 🔓"
                else            -> appInfo.appName
            }

            // Schedule subtitle
            val schedule = appInfo.fromTime ?: ""
            if (schedule.isNotEmpty()) {
                binding.timePeriodInfo.text = "Schedule: $schedule"
                binding.timePeriodInfo.visibility = ViewGroup.VISIBLE
            } else {
                binding.timePeriodInfo.visibility = ViewGroup.GONE
            }

            // ── Delete button ────────────────────────────────────────────────
            binding.delete.setOnClickListener {
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION || pos >= list.size) return@setOnClickListener

                val app = list[pos]

                // Check per-item strict mode
                if (ItemStrictModeManager.isLocked(context, app.packageName)) {
                    showItemStrictModeDialog(app)
                    return@setOnClickListener
                }

                // No lock — confirm and remove
                list.removeAt(pos)
                notifyItemRemoved(pos)
                onItemRemovedCallback?.invoke(app)
            }

            // Long-press delete → set/manage strict mode for this app
            binding.delete.setOnLongClickListener {
                showStrictModeSetupDialog(appInfo)
                true
            }

            // ── Timer button — set schedule ──────────────────────────────────
            binding.btnSetTimePeriod.setOnClickListener {
                onSetTimePeriodCallback?.invoke(appInfo)
            }

            // Long-press timer → also opens strict mode (discoverable via both buttons)
            binding.btnSetTimePeriod.setOnLongClickListener {
                showStrictModeSetupDialog(appInfo)
                true
            }
        }

        // ── Per-item strict mode — locked state dialog ───────────────────────
        private fun showItemStrictModeDialog(appInfo: AppInfo) {
            val key = appInfo.packageName
            val prefs = context.getSharedPreferences("ItemStrictModePrefs", Context.MODE_PRIVATE)
            val reqTime = prefs.getLong("strict_req_$key", 0L)

            val statusText = TextView(context).apply {
                textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
                textSize = 16f
                setPadding(40, 40, 40, 40)
                text = ItemStrictModeManager.getStatusText(context, key)
            }

            val h = Handler(Looper.getMainLooper())
            val r = object : Runnable {
                override fun run() {
                    statusText.text = ItemStrictModeManager.getStatusText(context, key)
                    if (ItemStrictModeManager.isLocked(context, key)) h.postDelayed(this, 1000)
                }
            }
            h.post(r)

            val builder = AlertDialog.Builder(context)
                .setTitle("🔒 ${appInfo.appName} is locked")
                .setView(statusText)

            when {
                reqTime == 0L -> builder.setPositiveButton("Request Unlock") { _, _ ->
                    TypeToAccessDialog.show(context, "Verify to request unlock") {
                        ItemStrictModeManager.requestUnlock(context, key)
                        Toast.makeText(context, "Unlock timer started!", Toast.LENGTH_SHORT).show()
                    }
                }
                ItemStrictModeManager.isLocked(context, key) ->
                    builder.setNegativeButton("Cancel Request") { _, _ ->
                        ItemStrictModeManager.cancelRequest(context, key)
                        Toast.makeText(context, "Request cancelled.", Toast.LENGTH_SHORT).show()
                    }
                else -> builder.setPositiveButton("Remove App Now") { _, _ ->
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION && pos < list.size) {
                        val app = list[pos]
                        list.removeAt(pos)
                        notifyItemRemoved(pos)
                        ItemStrictModeManager.clearItem(context, key)
                        onItemRemovedCallback?.invoke(app)
                    }
                }
            }

            val dialog = builder.create()
            dialog.setOnDismissListener { h.removeCallbacks(r) }
            dialog.show()
        }

        // ── Per-item strict mode — setup dialog (long-press) ─────────────────
        private fun showStrictModeSetupDialog(appInfo: AppInfo) {
            val key = appInfo.packageName
            val currentHours = ItemStrictModeManager.getHours(context, key)

            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(50, 40, 50, 10)
            }

            val statusText = TextView(context).apply {
                text = if (currentHours > 0)
                    "Current strict mode: ${currentHours}h delay\n${ItemStrictModeManager.getStatusText(context, key)}"
                else
                    "No strict mode set for ${appInfo.appName}."
                setPadding(0, 0, 0, 24)
            }

            val hoursInput = EditText(context).apply {
                hint = "Hours delay (e.g. 24). Set 0 to disable."
                inputType = InputType.TYPE_CLASS_NUMBER
                if (currentHours > 0) setText(currentHours.toString())
            }

            layout.addView(statusText)
            layout.addView(hoursInput)

            AlertDialog.Builder(context)
                .setTitle("Strict Mode — ${appInfo.appName}")
                .setView(layout)
                .setPositiveButton("Save") { _, _ ->
                    val hours = hoursInput.text.toString().toIntOrNull() ?: 0
                    if (hours <= 0) {
                        ItemStrictModeManager.clearItem(context, key)
                        Toast.makeText(context, "Strict mode removed for ${appInfo.appName}", Toast.LENGTH_SHORT).show()
                    } else {
                        ItemStrictModeManager.setStrictMode(context, key, hours)
                        Toast.makeText(context, "Strict mode set: ${hours}h delay to remove ${appInfo.appName}", Toast.LENGTH_SHORT).show()
                    }
                    notifyItemChanged(adapterPosition)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    fun setOnItemRemovedListener(callback: (AppInfo) -> Unit) {
        this.onItemRemovedCallback = callback
    }

    fun setOnSetTimePeriodListener(callback: (AppInfo) -> Unit) {
        this.onSetTimePeriodCallback = callback
    }
}
