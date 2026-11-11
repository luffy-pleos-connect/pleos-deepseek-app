package ai.moca.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import ai.pleos.ds.R
import moca.core.TrendPoint

/**
 * 종합 점수 추이.
 * 세로 범위는 점의 최솟값과 60 중 작은 값에서 10 을 뺀 값(0 미만은 0)부터 100 까지다.
 * 범위 안의 등급 경계(90 S, 80 A, 70 B, 60 C)만 왼쪽에 적는다.
 * 마지막 점 옆에 그 값을 적는다. 움직임은 없다.
 */
class TrendGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var points: List<TrendPoint> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gradeLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val valueLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }

    override fun onDraw(canvas: Canvas) {
        if (points.size < 2) return
        val values = points.mapNotNull { it.overall }
        if (values.isEmpty()) return
        val density = resources.displayMetrics.density
        val floor = (minOf(values.min(), 60) - 10).coerceAtLeast(0)
        val span = (100 - floor).toFloat()

        gradeLabel.textSize = resources.getDimension(R.dimen.pleos_text_label_s)
        gradeLabel.color = context.getColor(R.color.pleos_text_dark_tertiary)
        valueLabel.textSize = resources.getDimension(R.dimen.pleos_text_body_m)
        valueLabel.color = context.getColor(R.color.pleos_text_dark_primary)

        val gap = 8f * density
        val padLeft = gradeLabel.measureText("S") + gap
        val padRight = valueLabel.measureText("100") + gap
        val padVert = valueLabel.textSize * 0.5f
        val plotLeft = padLeft
        val plotRight = width - padRight
        val plotTop = padVert
        val plotBottom = height - padVert
        if (plotRight <= plotLeft || plotBottom <= plotTop) return

        fun yOf(value: Int): Float {
            val ratio = (value - floor) / span
            return plotBottom - ratio * (plotBottom - plotTop)
        }

        fun xOf(index: Int): Float =
            plotLeft + (plotRight - plotLeft) * index / (points.size - 1)

        grid.color = context.getColor(R.color.pleos_text_dark_quaternary)
        grid.strokeWidth = density
        for ((mark, name) in GRADE_LINES) {
            if (mark < floor || mark > 100) continue
            val y = yOf(mark)
            canvas.drawLine(plotLeft, y, plotRight, y, grid)
            val baseline = y - (gradeLabel.ascent() + gradeLabel.descent()) / 2f
            canvas.drawText(name, plotLeft - gap, baseline, gradeLabel)
        }

        val active = context.getColor(R.color.pleos_informative_active)
        line.color = active
        line.strokeWidth = 4f * density
        dot.color = active
        var previous: Pair<Float, Float>? = null
        points.forEachIndexed { index, point ->
            val value = point.overall
            if (value == null) {
                previous = null
                return@forEachIndexed
            }
            val x = xOf(index)
            val y = yOf(value)
            val from = previous
            if (from != null) canvas.drawLine(from.first, from.second, x, y, line)
            canvas.drawCircle(x, y, 6f * density, dot)
            previous = x to y
        }

        val last = points.last().overall ?: return
        val x = xOf(points.lastIndex) + gap
        val y = yOf(last)
        val baseline = y - (valueLabel.ascent() + valueLabel.descent()) / 2f
        canvas.drawText(last.toString(), x, baseline, valueLabel)
    }

    private companion object {
        val GRADE_LINES = listOf(90 to "S", 80 to "A", 70 to "B", 60 to "C")
    }
}
