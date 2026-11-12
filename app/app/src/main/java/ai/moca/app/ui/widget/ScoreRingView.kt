package ai.moca.app.ui.widget

import android.animation.ValueAnimator
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
 * 차오름은 한 번만 하고, 반복하지 않는다.
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

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bounds = RectF()
    private var motion: ValueAnimator? = null

    /** 고리를 그 점수에 두고 움직이지 않는다. */
    fun hold(value: Int) {
        motion?.cancel()
        motion = null
        progress = value
    }

    /** 지난 점수에서 새 점수까지 한 번만 차오른다. 800ms, 반복 없음. */
    fun play(from: Int, to: Int) {
        motion?.cancel()
        val start = from.coerceIn(0, 100)
        val end = to.coerceIn(0, 100)
        if (start == end) {
            motion = null
            progress = end
            return
        }
        progress = start
        val anim = ValueAnimator.ofInt(start, end)
        anim.duration = 800L
        anim.repeatCount = 0
        anim.addUpdateListener { progress = it.animatedValue as Int }
        motion = anim
        anim.start()
    }

    override fun onDetachedFromWindow() {
        motion?.cancel()
        motion = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = resources.getDimension(ai.moca.app.R.dimen.moca_score_ring_stroke)
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        track.color = context.getColor(R.color.pleos_text_dark_quaternary)
        // 고리는 점수만 나타낸다. 위험은 「가장 먼저 할 일」과 카테고리 줄이 빨강으로 알린다.
        arc.color = context.getColor(R.color.pleos_informative_positive)
        val inset = stroke / 2f
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(bounds, track)
        val sweep = 360f * progress / 100f
        if (sweep > 0f) {
            canvas.drawArc(bounds, -90f, sweep, false, arc)
        }
    }
}
