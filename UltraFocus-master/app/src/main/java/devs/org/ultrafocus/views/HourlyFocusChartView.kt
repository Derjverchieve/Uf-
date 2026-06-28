package devs.org.ultrafocus.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import devs.org.ultrafocus.R
import devs.org.ultrafocus.model.HourlyFocusStats

/**
 * Draws a 24-bar chart showing focused minutes per hour of day.
 * Bar height = focused minutes. Bar colour = quality band of average focus score.
 *   Green  (≥75)  — high quality
 *   Amber  (≥45)  — medium quality
 *   Red    (<45)  — low quality
 *   Faint  (0 min) — no data this hour
 *
 * Call [setData] with a 24-element array (index = hour, 0–23) to update.
 */
class HourlyFocusChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var stats: Array<HourlyFocusStats> =
        Array(24) { HourlyFocusStats(it, 0f, 0.0) }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#18FFFFFF")
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#20FFFFFF")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#80FFFFFF")
    }
    private val yLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        color = Color.parseColor("#60FFFFFF")
    }
    private val rect = RectF()

    fun setData(newStats: Array<HourlyFocusStats>) {
        stats = newStats
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dp = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()

        val padL = 48f * dp
        val padB = 28f * dp
        val padT = 12f * dp
        val padR = 8f * dp
        val chartW = w - padL - padR
        val chartH = h - padB - padT

        val maxMin = stats.maxOf { it.totalMinutes }.coerceAtLeast(30f)
        val niceMax = when {
            maxMin <= 30f  -> 30f
            maxMin <= 60f  -> 60f
            maxMin <= 90f  -> 90f
            maxMin <= 120f -> 120f
            maxMin <= 180f -> 180f
            maxMin <= 240f -> 240f
            else           -> (Math.ceil((maxMin / 60.0)) * 60).toFloat()
        }
        val gridSteps = when {
            niceMax <= 60f  -> listOf(15f, 30f, 45f, 60f)
            niceMax <= 120f -> listOf(30f, 60f, 90f, 120f)
            else            -> listOf(60f, 120f, 180f, 240f).filter { it <= niceMax + 1f }
        }

        labelPaint.textSize  = 22f * dp
        yLabelPaint.textSize = 20f * dp

        // Grid + Y labels
        for (gMin in gridSteps) {
            val y = padT + chartH * (1f - gMin / niceMax)
            canvas.drawLine(padL, y, w - padR, y, gridPaint)
            val lbl = if (gMin >= 60f) "${(gMin / 60).toInt()}h" else "${gMin.toInt()}m"
            canvas.drawText(lbl, padL - 4f * dp, y + 7f * dp, yLabelPaint)
        }

        // Bars
        val slotW  = chartW / 24f
        val barW   = slotW * 0.72f
        val barGap = (slotW - barW) / 2f
        val cr     = 3f * dp

        for (i in 0..23) {
            val s = stats[i]
            val x = padL + i * slotW + barGap
            if (s.totalMinutes > 0f) {
                val bh = chartH * (s.totalMinutes / niceMax)
                rect.set(x, padT + chartH - bh, x + barW, padT + chartH)
                barPaint.color = scoreColor(s.avgScore)
                canvas.drawRoundRect(rect, cr, cr, barPaint)
            } else {
                rect.set(x, padT + chartH - 3f * dp, x + barW, padT + chartH)
                canvas.drawRoundRect(rect, cr, cr, emptyPaint)
            }
        }

        // X labels at 12a / 6a / 12p / 6p
        for ((hour, lbl) in listOf(0 to "12a", 6 to "6a", 12 to "12p", 18 to "6p")) {
            val x = padL + hour * slotW + slotW / 2f
            canvas.drawText(lbl, x, h - 5f * dp, labelPaint)
        }
    }

    private fun scoreColor(score: Double): Int = when {
        score >= 75.0 -> ContextCompat.getColor(context, R.color.unlocked_green)
        score >= 45.0 -> ContextCompat.getColor(context, R.color.soft_block_amber)
        else          -> ContextCompat.getColor(context, R.color.locked_red)
    }
}
