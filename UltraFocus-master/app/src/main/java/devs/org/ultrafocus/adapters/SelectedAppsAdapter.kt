package devs.org.ultrafocus.adapters

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import devs.org.ultrafocus.R
import devs.org.ultrafocus.databinding.SelectedAppItemBinding
import devs.org.ultrafocus.model.AppInfo
import devs.org.ultrafocus.utils.ItemStrictModeManager
import devs.org.ultrafocus.utils.SoftBlockManager
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

            val isStrictLocked   = ItemStrictModeManager.isLocked(context, appInfo.packageName)
            val isStrictEnabled  = ItemStrictModeManager.isEnabled(context, appInfo.packageName)
            val isSoft           = SoftBlockManager.isSoftBlocked(context, appInfo.packageName)
            val isSoftLocked     = SoftBlockManager.isSoftBlockLocked(context, appInfo.packageName)
            val isSoftStrict     = SoftBlockManager.isSoftBlockStrictEnabled(context, appInfo.packageName)

            // ── App name label ───────────────────────────────────────────────
            binding.label.text = when {
                isStrictLocked  -> "${appInfo.appName} 🔒"
                isStrictEnabled -> "${appInfo.appName} 🔓"
                else            -> appInfo.appName
            }

            // ── Sub-label (schedule / soft block indicator) ───────────────────
            val schedule = appInfo.fromTime ?: ""
            when {
                isSoft && schedule.isNotEmpty() -> {
                    val lockIcon = if (isSoftLocked) " 🔒" else if (isSoftStrict) " 🔓" else ""
                    val mins = SoftBlockManager.getAccessDurationMinutes(context, appInfo.packageName)
                    val len  = SoftBlockManager.getCodeLength(context, appInfo.packageName)
                    binding.timePeriodInfo.text = "Soft block$lockIcon  •  ${mins}m access  •  ${len}c code  •  Schedule: $schedule"
                    binding.timePeriodInfo.visibility = ViewGroup.VISIBLE
                    binding.timePeriodInfo.setTextColor(
                        ContextCompat.getColor(context, R.color.soft_block_amber)
                    )
                }
                isSoft -> {
                    val lockIcon = if (isSoftLocked) " 🔒" else if (isSoftStrict) " 🔓" else ""
                    val mins = SoftBlockManager.getAccessDurationMinutes(context, appInfo.packageName)
                    val len  = SoftBlockManager.getCodeLength(context, appInfo.packageName)
                    binding.timePeriodInfo.text = "Soft block$lockIcon  •  ${mins}m access  •  ${len}c code"
                    binding.timePeriodInfo.visibility = ViewGroup.VISIBLE
                    binding.timePeriodInfo.setTextColor(
                        ContextCompat.getColor(context, R.color.soft_block_amber)
                    )
                }
                schedule.isNotEmpty() -> {
                    binding.timePeriodInfo.text = "Schedule: $schedule"
                    binding.timePeriodInfo.visibility = ViewGroup.VISIBLE
                    binding.timePeriodInfo.setTextColor(
                        ContextCompat.getColor(context, R.color.text_secondary)
                    )
                }
                else -> binding.timePeriodInfo.visibility = ViewGroup.GONE
            }

            // ── Soft block button ────────────────────────────────────────────
            val shieldTint = when {
                isSoft && isSoftLocked -> ContextCompat.getColor(context, R.color.locked_red)
                isSoft                 -> ContextCompat.getColor(context, R.color.soft_block_amber)
                else                   -> ContextCompat.getColor(context, R.color.text_secondary)
            }
            binding.btnSoftBlock.setColorFilter(shieldTint)

            // Single tap — toggle soft block (blocked if strictly locked)
            binding.btnSoftBlock.setOnClickListener {
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val app = list[pos]
                val currentlySoft = SoftBlockManager.isSoftBlocked(context, app.packageName)

                // Cannot enable soft block while item strict mode is locked (bypass loophole)
                if (!currentlySoft && ItemStrictModeManager.isLocked(context, app.packageName)) {
                    Toast.makeText(
                        context,
                        "${app.appName} is strictly locked. Remove the strict mode lock first.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                // Cannot disable soft block while soft block strict mode is locked
                if (currentlySoft && SoftBlockManager.isSoftBlockLocked(context, app.packageName)) {
                    showSoftBlockLockDialog(app)
                    return@setOnClickListener
                }

                SoftBlockManager.setSoftBlock(context, app.packageName, !currentlySoft)
                if (currentlySoft) {
                    // Clearing soft block also clears its strict mode
                    SoftBlockManager.clearSoftBlockStrict(context, app.packageName)
                }
                val msg = if (!currentlySoft)
                    "${app.appName} set to soft block — UUID challenge required to open"
                else
                    "${app.appName} switched back to hard block"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                notifyItemChanged(pos)
            }

            // Long press — open soft block settings (code length, access time, strict mode)
            binding.btnSoftBlock.setOnLongClickListener {
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener true
                val app = list[pos]
                if (!SoftBlockManager.isSoftBlocked(context, app.packageName)) {
                    Toast.makeText(context, "Enable soft block first to configure its settings.", Toast.LENGTH_SHORT).show()
                } else if (SoftBlockManager.isSoftBlockLocked(context, app.packageName)) {
                    showSoftBlockLockDialog(app)
                } else {
                    showSoftBlockSettingsDialog(app)
                }
                true
            }

            // ── Delete button ────────────────────────────────────────────────
            binding.delete.setOnClickListener {
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION || pos >= list.size) return@setOnClickListener

                val app = list[pos]
                if (ItemStrictModeManager.isLocked(context, app.packageName)) {
                    showItemStrictModeDialog(app)
                    return@setOnClickListener
                }
                // Cannot delete while soft block strict mode is locked — deletion
                // would silently clear the soft block, bypassing the strict lock.
                if (SoftBlockManager.isSoftBlockLocked(context, app.packageName)) {
                    showSoftBlockLockDialog(app)
                    return@setOnClickListener
                }
                list.removeAt(pos)
                notifyItemRemoved(pos)
                SoftBlockManager.setSoftBlock(context, app.packageName, false)
                SoftBlockManager.clearSoftBlockStrict(context, app.packageName)
                onItemRemovedCallback?.invoke(app)
            }

            binding.delete.setOnLongClickListener {
                showStrictModeSetupDialog(appInfo)
                true
            }

            // ── Timer button — schedule ──────────────────────────────────────
            binding.btnSetTimePeriod.setOnClickListener {
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val app = list[pos]

                // FIX: schedule cannot be changed while the app is strictly locked —
                // changing the time window is a bypass (could open a gap outside the
                // current blocking hours to access the app).
                if (ItemStrictModeManager.isLocked(context, app.packageName)) {
                    Toast.makeText(
                        context,
                        "${app.appName} is strictly locked. Unlock it before changing the schedule.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                // Also block schedule changes while soft block strict mode is locked —
                // a schedule change could open a window where the app is unblocked.
                if (SoftBlockManager.isSoftBlockLocked(context, app.packageName)) {
                    Toast.makeText(
                        context,
                        "${app.appName}'s soft block is strictly locked. Unlock it before changing the schedule.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                onSetTimePeriodCallback?.invoke(app)
            }

            binding.btnSetTimePeriod.setOnLongClickListener {
                showStrictModeSetupDialog(appInfo)
                true
            }
        }

        // ── Soft block lock dialog (mirrors ItemStrictModeManager lock dialog) ───
        private fun showSoftBlockLockDialog(appInfo: AppInfo) {
            val key = appInfo.packageName
            val prefs = context.getSharedPreferences("SoftBlockStrictPrefs", Context.MODE_PRIVATE)
            val reqTime = prefs.getLong("sb_strict_req_$key", 0L)

            val statusText = TextView(context).apply {
                textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
                textSize = 16f
                setPadding(40, 40, 40, 40)
                text = SoftBlockManager.getSoftBlockStatusText(context, key)
            }

            val h = Handler(Looper.getMainLooper())
            val r = object : Runnable {
                override fun run() {
                    statusText.text = SoftBlockManager.getSoftBlockStatusText(context, key)
                    if (SoftBlockManager.isSoftBlockLocked(context, key)) h.postDelayed(this, 1000)
                }
            }
            h.post(r)

            val builder = AlertDialog.Builder(context)
                .setTitle("🔒 Soft block locked — ${appInfo.appName}")
                .setView(statusText)

            when {
                reqTime == 0L -> builder.setPositiveButton("Request Unlock") { _, _ ->
                    TypeToAccessDialog.show(context, "Verify to request soft block unlock") {
                        SoftBlockManager.requestSoftBlockUnlock(context, key)
                        Toast.makeText(context, "Soft block unlock timer started!", Toast.LENGTH_SHORT).show()
                    }
                }
                SoftBlockManager.isSoftBlockLocked(context, key) ->
                    builder.setNegativeButton("Cancel Request") { _, _ ->
                        SoftBlockManager.cancelSoftBlockUnlockRequest(context, key)
                        Toast.makeText(context, "Request cancelled.", Toast.LENGTH_SHORT).show()
                    }
                else -> builder.setPositiveButton("Disable Soft Block Now") { _, _ ->
                    val pos = adapterPosition
                    SoftBlockManager.setSoftBlock(context, key, false)
                    SoftBlockManager.clearSoftBlockStrict(context, key)
                    Toast.makeText(context, "${appInfo.appName} soft block removed.", Toast.LENGTH_SHORT).show()
                    if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
                }
            }

            val dialog = builder.create()
            dialog.setOnDismissListener { h.removeCallbacks(r) }
            dialog.show()
        }

        // ── Soft block settings dialog (long-press shield) ───────────────────
        private fun showSoftBlockSettingsDialog(appInfo: AppInfo) {
            val key = appInfo.packageName

            val currentLen      = SoftBlockManager.getCodeLength(context, key)
            val currentMins     = SoftBlockManager.getAccessDurationMinutes(context, key)
            val currentStrict   = SoftBlockManager.getSoftBlockStrictHours(context, key)

            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(60, 40, 60, 10)
            }

            fun label(text: String) = TextView(context).apply {
                this.text = text
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(0, 16, 0, 4)
            }

            fun numberInput(hint: String, current: Int) = EditText(context).apply {
                this.hint = hint
                inputType = InputType.TYPE_CLASS_NUMBER
                if (current > 0) setText(current.toString())
            }

            val codeLenInput    = numberInput("Characters (${SoftBlockManager.MIN_CODE_LENGTH}–${SoftBlockManager.MAX_CODE_LENGTH})", currentLen)
            val accessMinsInput = numberInput("Minutes before being booted out again (min 1)", currentMins)
            val strictInput     = numberInput("Hours lock delay (0 = no strict mode)", currentStrict)

            layout.addView(label("Challenge code length (characters):"))
            layout.addView(codeLenInput)
            layout.addView(label("Access time after passing challenge (minutes):"))
            layout.addView(accessMinsInput)
            layout.addView(label("Soft block strict mode — delay before turning off (hours, 0 = disabled):"))
            layout.addView(strictInput)

            AlertDialog.Builder(context)
                .setTitle("Soft Block Settings — ${appInfo.appName}")
                .setView(layout)
                .setPositiveButton("Save") { _, _ ->
                    val newLen    = codeLenInput.text.toString().toIntOrNull()
                        ?.coerceIn(SoftBlockManager.MIN_CODE_LENGTH, SoftBlockManager.MAX_CODE_LENGTH)
                        ?: currentLen
                    val newMins   = accessMinsInput.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: currentMins
                    val newStrict = strictInput.text.toString().toIntOrNull() ?: 0

                    SoftBlockManager.setCodeLength(context, key, newLen)
                    SoftBlockManager.setAccessDurationMinutes(context, key, newMins)
                    SoftBlockManager.setSoftBlockStrict(context, key, newStrict)

                    val strictMsg = if (newStrict > 0) " | Strict: ${newStrict}h" else ""
                    Toast.makeText(
                        context,
                        "${appInfo.appName}: ${newLen}c code | ${newMins}m access$strictMsg",
                        Toast.LENGTH_LONG
                    ).show()
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
                }
                .setNegativeButton("Cancel", null)
                .show()
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
                        SoftBlockManager.setSoftBlock(context, key, false)
                        SoftBlockManager.clearSoftBlockStrict(context, key)
                        onItemRemovedCallback?.invoke(app)
                    }
                }
            }

            val dialog = builder.create()
            dialog.setOnDismissListener { h.removeCallbacks(r) }
            dialog.show()
        }

        // ── Per-item strict mode — setup dialog (long-press delete or timer) ─
        private fun showStrictModeSetupDialog(appInfo: AppInfo) {
            val key = appInfo.packageName

            // BUG FIX: redirect to lock dialog if already locked — prevents editing
            // the delay value to 0 as a bypass while strict mode is active.
            if (ItemStrictModeManager.isLocked(context, key)) {
                showItemStrictModeDialog(appInfo)
                return
            }

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
