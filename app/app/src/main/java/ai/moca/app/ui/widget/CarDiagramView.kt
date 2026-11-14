package ai.moca.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import ai.moca.app.R
import moca.core.Category
import moca.core.CategoryStatus
import moca.core.WheelArea

/**
 * 상태관리 화면의 2D 자동차 그림(최종정리 5장 3번).
 * 디자이너 그림이 오기 전의 자리표시다. 위에서 본 차를 단순하게 그리고, 구역 5개를 상태 색으로 칠한다.
 * 바퀴는 타이어 구역 색으로 칠하되 저압인 바퀴만 위험 색으로 따로 칠한다.
 * 구역 좌표는 [CarDiagramLayout] 이 정하고, 이 클래스는 그리기와 누르기만 맡는다.
 */
class CarDiagramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var statuses: Map<Category, CategoryStatus> = emptyMap()
    private var lowWheels: Set<WheelArea> = emptySet()

    /** 영역을 누르면 그 구역을 알려 준다. 바퀴는 타이어다. */
    var onZoneClick: ((Category) -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    init {
        isClickable = true
        isFocusable = true
    }

    fun render(statuses: Map<Category, CategoryStatus>, lowWheels: Set<WheelArea>) {
        this.statuses = statuses
        this.lowWheels = lowWheels
        contentDescription = descriptionOf()
        invalidate()
    }

    private fun descriptionOf(): String {
        val parts = Category.entries.map { category ->
            val status = statuses[category] ?: CategoryStatus.UNMEASURED
            context.getString(R.string.car_zone_state, labelOf(category), status.label)
        }
        val low = WheelArea.entries.filter { it in lowWheels }
        val all = if (low.isEmpty()) {
            parts
        } else {
            parts + context.getString(
                R.string.car_wheel_low,
                low.joinToString(", ") { it.label },
            )
        }
        return context.getString(R.string.car_diagram_description, all.joinToString(", "))
    }

    private fun labelOf(category: Category): String = when (category) {
        Category.POWER -> context.getString(R.string.home_cat_power)
        Category.BRAKE_STEERING -> context.getString(R.string.home_cat_brake)
        Category.TIRE -> context.getString(R.string.home_cat_tire)
        Category.ELECTRIC -> context.getString(R.string.home_cat_electric)
        Category.SAFETY -> context.getString(R.string.home_cat_safety)
    }

    private fun colorOf(status: CategoryStatus?): Int = ContextCompat.getColor(
        context,
        when (status) {
            CategoryStatus.OK -> ai.pleos.ds.R.color.pleos_informative_positive
            CategoryStatus.CAUTION -> ai.pleos.ds.R.color.pleos_regulation_yellow
            CategoryStatus.DANGER -> ai.pleos.ds.R.color.pleos_regulation_red
            CategoryStatus.UNMEASURED, null -> ai.pleos.ds.R.color.pleos_text_dark_tertiary
        },
    )

    private fun statusOf(category: Category): CategoryStatus = statuses[category] ?: CategoryStatus.UNMEASURED

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val density = resources.displayMetrics.density
        val body = CarDiagramLayout.bodyZones(width.toFloat(), height.toFloat())
        val wheels = CarDiagramLayout.wheelZones(width.toFloat(), height.toFloat())

        stroke.strokeWidth = 2f * density
        label.textSize = resources.getDimension(ai.pleos.ds.R.dimen.pleos_text_body_m)
        label.color = ContextCompat.getColor(context, ai.pleos.ds.R.color.pleos_text_dark_primary)

        drawWheels(canvas, wheels)
        drawZones(canvas, body)
    }

    private fun drawWheels(canvas: Canvas, wheels: Map<WheelArea, ZoneRect>) {
        val tire = statusOf(Category.TIRE)
        for (wheel in WheelArea.entries) {
            val rect = wheels.getValue(wheel)
            val status = when {
                tire == CategoryStatus.UNMEASURED -> CategoryStatus.UNMEASURED
                wheel in lowWheels -> CategoryStatus.DANGER
                else -> tire
            }
            val color = colorOf(status)
            fill.color = color
            fill.alpha = FILL_ALPHA
            stroke.color = color
            stroke.alpha = STROKE_ALPHA
            val rounded = RectF(rect.left, rect.top, rect.right, rect.bottom)
            val radius = minOf(rounded.width(), rounded.height()) * WHEEL_RADIUS_RATIO
            canvas.drawRoundRect(rounded, radius, radius, fill)
            canvas.drawRoundRect(rounded, radius, radius, stroke)
        }
    }

    /** 차체는 앞뒤를 깎은 한 덩어리로 그리고, 그 위에 구역 색을 칠한다. */
    private fun drawZones(canvas: Canvas, body: Map<Category, ZoneRect>) {
        val outline = CarDiagramLayout.bodyOutline(width.toFloat(), height.toFloat())
        val car = bodyPath(outline)

        canvas.save()
        canvas.clipPath(car)
        for ((category, rect) in body) {
            fill.color = colorOf(statusOf(category))
            fill.alpha = FILL_ALPHA
            canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, fill)
        }
        canvas.restore()

        stroke.color = ContextCompat.getColor(context, ai.pleos.ds.R.color.pleos_text_dark_tertiary)
        stroke.alpha = STROKE_ALPHA
        canvas.drawPath(car, stroke)

        // 같은 색인 구역끼리 붙어 보이지 않게 경계를 얇게 긋는다.
        stroke.alpha = DIVIDER_ALPHA
        for (category in DIVIDERS) {
            val rect = body.getValue(category)
            canvas.drawLine(rect.left, rect.bottom, rect.right, rect.bottom, stroke)
        }

        for ((category, rect) in body) {
            val baseline = (rect.top + rect.bottom) / 2f - (label.ascent() + label.descent()) / 2f
            canvas.drawText(labelOf(category), (rect.left + rect.right) / 2f, baseline, label)
        }
    }

    /** 위에서 본 차 실루엣. 앞(보닛)이 좁고 뒤쪽이 조금 넓다. */
    private fun bodyPath(outline: ZoneRect): Path {
        val w = outline.width
        val h = outline.height
        val left = outline.left
        val top = outline.top
        val right = outline.right
        val bottom = outline.bottom
        val path = Path()
        path.moveTo(left + w * NOSE_INSET, top)
        path.lineTo(right - w * NOSE_INSET, top)
        path.cubicTo(right, top + h * 0.05f, right, top + h * 0.10f, right, top + h * SHOULDER)
        path.lineTo(right, bottom - h * SHOULDER)
        path.cubicTo(right, bottom - h * 0.06f, right - w * 0.02f, bottom, right - w * TAIL_INSET, bottom)
        path.lineTo(left + w * TAIL_INSET, bottom)
        path.cubicTo(left + w * 0.02f, bottom, left, bottom - h * 0.06f, left, bottom - h * SHOULDER)
        path.lineTo(left, top + h * SHOULDER)
        path.cubicTo(left, top + h * 0.10f, left, top + h * 0.05f, left + w * NOSE_INSET, top)
        path.close()
        return path
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val zone = CarDiagramLayout.zoneAt(width.toFloat(), height.toFloat(), event.x, event.y)
                if (zone != null) {
                    performClick()
                    onZoneClick?.invoke(zone)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        const val FILL_ALPHA = 52
        const val DIVIDER_ALPHA = 80
        val DIVIDERS = listOf(Category.POWER, Category.BRAKE_STEERING, Category.ELECTRIC)
        const val STROKE_ALPHA = 170
        const val WHEEL_RADIUS_RATIO = 0.35f
        const val NOSE_INSET = 0.14f
        const val TAIL_INSET = 0.06f
        const val SHOULDER = 0.22f
    }
}
