package devs.org.ultrafocus.activities

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import devs.org.ultrafocus.database.AppDatabase
import devs.org.ultrafocus.databinding.ActivityAnalyticsBinding
import devs.org.ultrafocus.model.HourlyFocusStats
import devs.org.ultrafocus.repository.DeepWorkRepository
import devs.org.ultrafocus.utils.DurationFormatter
import kotlinx.coroutines.launch
import java.util.Calendar

class AnalyticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAnalyticsBinding
    private lateinit var repository: DeepWorkRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAnalyticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        repository = DeepWorkRepository(AppDatabase.getDatabase(this))
        binding.toolbar.setNavigationOnClickListener { finish() }
        loadAnalytics()
    }

    private fun loadAnalytics() {
        lifecycleScope.launch {
            val sessions = repository.getAllCompletedSessions()

            if (sessions.isEmpty()) {
                binding.layoutEmpty.visibility = View.VISIBLE
                binding.layoutContent.visibility = View.GONE
                return@launch
            }

            binding.layoutEmpty.visibility = View.GONE
            binding.layoutContent.visibility = View.VISIBLE

            // ── All-time summary ────────────────────────────────────────────
            val totalFocusedMs = sessions.sumOf { it.focusedTimeMs }
            val scores = sessions.mapNotNull { it.focusScore }
            val avgScore = if (scores.isEmpty()) 0.0 else scores.average()

            binding.txtTotalFocused.text = DurationFormatter.formatCompact(totalFocusedMs)
            binding.txtTotalSessions.text = "${sessions.size}"
            binding.txtAvgScore.text = avgScore.toInt().toString()

            // ── Hourly aggregation ──────────────────────────────────────────
            val cal = Calendar.getInstance()
            val hourTotalMs = LongArray(24)
            val hourScores = Array(24) { mutableListOf<Int>() }

            for (session in sessions) {
                cal.timeInMillis = session.startTime
                val h = cal.get(Calendar.HOUR_OF_DAY)
                hourTotalMs[h] += session.focusedTimeMs
                session.focusScore?.let { hourScores[h].add(it) }
            }

            val hourlyStats = Array(24) { h ->
                HourlyFocusStats(
                    hour = h,
                    totalMinutes = hourTotalMs[h] / 60000f,
                    avgScore = if (hourScores[h].isEmpty()) 0.0 else hourScores[h].average()
                )
            }

            binding.chartHourly.setData(hourlyStats)

            // ── Best hour ───────────────────────────────────────────────────
            val best = hourlyStats.filter { it.totalMinutes > 0f }
                .maxByOrNull { it.totalMinutes }
            if (best != null) {
                val h = best.hour
                val label = when {
                    h == 0  -> "12 AM"
                    h < 12  -> "$h AM"
                    h == 12 -> "12 PM"
                    else    -> "${h - 12} PM"
                }
                binding.txtBestHour.text =
                    "$label  ·  ${DurationFormatter.formatCompact((best.totalMinutes * 60000).toLong())} focused"
            } else {
                binding.txtBestHour.text = "—"
            }
        }
    }
}
