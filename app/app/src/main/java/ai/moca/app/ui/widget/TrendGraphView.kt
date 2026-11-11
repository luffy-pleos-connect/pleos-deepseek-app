package ai.moca.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import ai.pleos.ds.R
import moca.core.TrendPoint

/**
 * 종합 점수 추이. 세로축은 0~100, 등급 경계는 옅은 가로선.
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
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }

    override fun onDraw(canvas: Canvas) {
        if (points.size < 2) return
        val density = resources.displayMetrics.density
        val padLeft = 8f * density
        val padRight = 72f * density
        val padVert = 12f * density
        val plotLeft = padLeft
        val plotRight = width - padRight
        val plotTop = padVert
        val plotBottom = height - padVert
        if (plotRight <= plotLeft || plotBottom <= plotTop) return

        fun yOf(value: Int): Float {
            val ratio = value.coerceIn(0, 100) / 100f
            return plotBottom - ratio * (plotBottom - plotTop)
        }

        fun xOf(index: Int): Float =
            plotLeft + (plotRight - plotLeft) * index / (points.size - 1)

        grid.color = context.getColor(R.color.pleos_text_dark_quaternary)
        grid.strokeWidth = density
        for (mark in intArrayOf(60, 70, 80, 90)) {
            val y = yOf(mark)
            canvas.drawLine(plotLeft, y, plotRight, y, grid)
        }

        val active = context.getColor(R.color.pleos_informative_active)
        line.color = active
        line.strokeWidth = 3f * density
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
            canvas.drawCircle(x, y, 5f * density, dot)
            previous = x to y
        }

        val last = points.last().overall ?: return
        label.color = context.getColor(R.color.pleos_text_dark_primary)
        label.textSize = resources.getDimension(R.dimen.pleos_text_label_s)
        val x = xOf(points.lastIndex) + 8f * density
        val y = yOf(last) + label.textSize * 0.35f
        canvas.drawText(last.toString(), x, y, label)
    }
}
