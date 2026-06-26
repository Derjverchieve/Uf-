package devs.org.ultrafocus.activities

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import devs.org.ultrafocus.adapters.MonthlyStatsAdapter
import devs.org.ultrafocus.database.AppDatabase
import devs.org.ultrafocus.databinding.ActivityLeaderboardBinding
import devs.org.ultrafocus.model.FocusSession
import devs.org.ultrafocus.model.MonthlyStats
import devs.org.ultrafocus.repository.DeepWorkRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class LeaderboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLeaderboardBinding
    private lateinit var repository: DeepWorkRepository
    private lateinit var adapter: MonthlyStatsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLeaderboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        repository = DeepWorkRepository(AppDatabase.getDatabase(this))

        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = MonthlyStatsAdapter(emptyList())
        binding.recyclerLeaderboard.layoutManager = LinearLayoutManager(this)
        binding.recyclerLeaderboard.adapter = adapter

        loadLeaderboard()
    }

    private fun loadLeaderboard() {
        lifecycleScope.launch {
            val sessions = repository.getAllCompletedSessions()
            val stats = buildMonthlyStats(sessions)
            if (stats.isEmpty()) {
                binding.txtEmpty.visibility = View.VISIBLE
                binding.recyclerLeaderboard.visibility = View.GONE
                binding.layoutHeader.visibility = View.GONE
            } else {
                binding.txtEmpty.visibility = View.GONE
                binding.recyclerLeaderboard.visibility = View.VISIBLE
                binding.layoutHeader.visibility = View.VISIBLE
                adapter.submitList(stats)
            }
        }
    }

    /**
     * Groups all completed sessions by calendar month, computes per-month stats,
     * sorts by EFM descending, and assigns ranks.
     *
     * EFM (Effective Focus Minutes) = Σ (focusScore / 100.0) × (focusedTimeMs / 60000.0)
     * per session, summed for the month. This is quality-adjusted output: same clock-hours
     * with more breaks produce lower EFM. Does NOT double-count — focus time is only
     * multiplied by the score rate, never by itself again.
     */
    private fun buildMonthlyStats(sessions: List<FocusSession>): List<MonthlyStats> {
        if (sessions.isEmpty()) return emptyList()

        val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        val cal = Calendar.getInstance()

        // Group sessions by (year, 1-based month)
        val grouped = sessions.groupBy { session ->
            cal.timeInMillis = session.startTime
            Pair(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
        }

        val unsorted = grouped.map { (yearMonth, monthSessions) ->
            val (year, month) = yearMonth

            // Point calendar at the 1st of this month just for label formatting
            cal.set(year, month - 1, 1, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val label = monthFormat.format(cal.time)

            val totalFocusedMs = monthSessions.sumOf { it.focusedTimeMs }

            val avgScore = monthSessions
                .mapNotNull { it.focusScore }
                .let { scores -> if (scores.isNotEmpty()) scores.average() else 0.0 }

            val efm = monthSessions.sumOf { session ->
                val scoreRate = (session.focusScore ?: 0) / 100.0
                val focusMinutes = session.focusedTimeMs / 60000.0
                scoreRate * focusMinutes
            }

            MonthlyStats(
                year = year,
                month = month,
                label = label,
                sessionCount = monthSessions.size,
                totalFocusedTimeMs = totalFocusedMs,
                avgFocusScore = avgScore,
                efm = efm,
                rank = 0 // filled in below
            )
        }

        // Sort by EFM descending and assign 1-based ranks
        return unsorted
            .sortedByDescending { it.efm }
            .mapIndexed { index, stats -> stats.copy(rank = index + 1) }
    }
}
