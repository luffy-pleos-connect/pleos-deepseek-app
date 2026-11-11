package ai.moca.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import ai.pleos.ds.R

/**
 * 종합 점수 0~100 을 바탕 고리와 찬 호로만 그린다.
 * 숫자는 그리지 않는다. 가운데 점수는 겹친 TextView 가 맡는다.
 * 반복 애니메이션은 없다.
 */
class ScoreRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var progress: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    /** 빨간 경고로 등급이 묶였으면 호의 색이 부정 색이다. */
    var cappedByRed: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bounds = RectF()

    override fun onDraw(canvas: Canvas) {
        val stroke = resources.displayMetrics.density * 12f
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        track.color = context.getColor(R.color.pleos_text_dark_quaternary)
        arc.color = context.getColor(
            if (cappedByRed) R.color.pleos_informative_negative else R.color.pleos_informative_positive,
        )
        val inset = stroke / 2f
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(bounds, track)
        val sweep = 360f * progress / 100f
        if (sweep > 0f) {
            canvas.drawArc(bounds, -90f, sweep, false, arc)
        }
    }
}
