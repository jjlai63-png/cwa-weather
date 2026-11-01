package tw.local.seaweather

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.PI
import kotlin.math.cos

data class TideMark(val epochSecond: Long, val heightCm: Int, val high: Boolean, val label: String)

/** Half a cosine between consecutive high and low waters, which gives the tide its sine shape. */
fun tideHeightAt(marks: List<TideMark>, epochSecond: Long): Double? {
    val next = marks.indexOfFirst { it.epochSecond >= epochSecond }
    if (next < 0) return null
    if (marks[next].epochSecond == epochSecond) return marks[next].heightCm.toDouble()
    if (next == 0) return null
    val a = marks[next - 1]
    val b = marks[next]
    val progress = (epochSecond - a.epochSecond).toDouble() / (b.epochSecond - a.epochSecond)
    return a.heightCm + (b.heightCm - a.heightCm) * (1 - cos(PI * progress)) / 2
}

/**
 * Columns are drawn on a per-period time scale, so the curve lines up with the
 * grid above it. A month of columns is too wide for one drawn path, so only the
 * part inside [viewport] is drawn; scrolling it must invalidate this view.
 */
@SuppressLint("ViewConstructor")
class TideCurveView(
    context: Context,
    private val periods: List<Pair<Long, Long>>,
    private val marks: List<TideMark>,
    private val columnWidth: Float,
    private val viewport: View,
    private val dayStarts: List<Float>,
    dayLineColor: Int,
    private val lineColor: Int,
    private val textColor: Int,
    background: Int,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        strokeJoin = Paint.Join.ROUND
        color = lineColor
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(46, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor))
    }
    private val dayLine = Paint().apply {
        color = dayLineColor
        strokeWidth = 2 * density
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lineColor }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 11 * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }

    init {
        setBackgroundColor(background)
    }

    private fun timeAt(x: Float): Long {
        val index = (x / columnWidth).toInt().coerceIn(0, periods.lastIndex)
        val (start, end) = periods[index]
        return start + ((x / columnWidth - index) * (end - start)).toLong()
    }

    private fun xAt(time: Long): Float? {
        val index = periods.indexOfFirst { time >= it.first && time < it.second }
        if (index < 0) return null
        val (start, end) = periods[index]
        return (index + (time - start).toFloat() / (end - start)) * columnWidth
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val onTimeline = marks.filter { xAt(it.epochSecond) != null }
        if (onTimeline.isEmpty()) return
        val top = onTimeline.maxOf { it.heightCm }.toFloat()
        val bottom = onTimeline.minOf { it.heightCm }.toFloat()
        val gutter = 24 * density
        val plot = height - 2 * gutter
        fun yAt(cm: Double): Float {
            val span = (top - bottom).coerceAtLeast(1f)
            return gutter + ((top - cm.toFloat()) / span).coerceIn(-0.15f, 1.15f) * plot
        }

        val margin = 80 * density
        val left = (viewport.scrollX - margin).coerceAtLeast(0f)
        val right = (viewport.scrollX + viewport.width + margin).coerceAtMost(width.toFloat())
        dayStarts.forEach { dayX ->
            if (dayX in left..right) canvas.drawLine(dayX, 0f, dayX, height.toFloat(), dayLine)
        }

        val curve = Path()
        var first: Float? = null
        var last = 0f
        var x = left
        while (x <= right) {
            tideHeightAt(marks, timeAt(x))?.let { cm ->
                if (first == null) {
                    first = x
                    curve.moveTo(x, yAt(cm))
                } else {
                    curve.lineTo(x, yAt(cm))
                }
                last = x
            }
            x += 2 * density
        }
        val start = first ?: return
        val area = Path(curve).apply {
            lineTo(last, height.toFloat())
            lineTo(start, height.toFloat())
            close()
        }
        canvas.drawPath(area, fill)
        canvas.drawPath(curve, line)

        onTimeline.forEach { mark ->
            val px = xAt(mark.epochSecond) ?: return@forEach
            if (px < left || px > right) return@forEach
            val py = yAt(mark.heightCm.toDouble())
            canvas.drawCircle(px, py, 3.5f * density, dot)
            val half = text.measureText(mark.label) / 2
            val labelX = px.coerceIn(half + density, width - half - density)
            val labelY = if (mark.high) py - 8 * density else py + 8 * density - text.ascent()
            canvas.drawText(mark.label, labelX, labelY, text)
        }
    }
}
