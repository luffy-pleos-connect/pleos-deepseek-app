package ai.moca.app.ui.widget

import moca.core.Category
import moca.core.WheelArea

/**
 * 자동차 그림의 한 영역. 화면 좌표는 View 의 왼쪽 위가 (0, 0) 이다.
 * 안드로이드 클래스를 쓰지 않으므로 JVM 단위 테스트가 그대로 돈다.
 */
data class ZoneRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x < right && y >= top && y < bottom

    fun overlaps(other: ZoneRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/** 그림의 영역 하나. 바퀴는 [wheel] 이 채워지고, 나머지는 null 이다. */
data class DiagramZone(val category: Category, val wheel: WheelArea?, val rect: ZoneRect)

/**
 * 2D 자동차 그림의 영역 좌표(최종정리 5장 3번).
 * 위에서 본 차를 보닛 → 차체 앞쪽 띠 → 가운데 바닥 → 실내 순으로 나누고, 바퀴 4개를 차체 밖에 둔다.
 * 영역은 서로 겹치지 않는다. 디자이너 그림이 오면 이 계산만 바꾼다.
 *
 * View 가 넓어도 차는 가로:세로 = [CAR_ASPECT] 를 지키고 가운데에 놓는다.
 * 바퀴가 [MIN_TOUCH_DP] 보다 좁아지면 차체를 그만큼 좁혀 누를 수 있는 크기를 지킨다.
 */
object CarDiagramLayout {

    /** 영역을 누를 수 있는 최소 크기(dp). */
    const val MIN_TOUCH_DP: Float = 64f

    /** 차의 가로:세로 비. 디자이너 그림이 정해지면 바꾼다. */
    const val CAR_ASPECT: Float = 0.80f

    /** 차 그림이 놓이는 네모(차체 + 바퀴). View 안에서 가운데 정렬한다. */
    fun carBox(width: Float, height: Float): ZoneRect {
        if (width <= 0f || height <= 0f) return ZoneRect(0f, 0f, 0f, 0f)
        val carWidth: Float
        val carHeight: Float
        if (width / height > CAR_ASPECT) {
            carHeight = height
            carWidth = height * CAR_ASPECT
        } else {
            carWidth = width
            carHeight = width / CAR_ASPECT
        }
        val left = (width - carWidth) / 2f
        val top = (height - carHeight) / 2f
        return ZoneRect(left, top, left + carWidth, top + carHeight)
    }

    private const val BODY_TOP = 0.02f
    private const val BODY_BOTTOM = 0.98f
    private const val SIDE_FRACTION = 0.20f
    private const val SIDE_MAX = 0.40f

    private const val BONNET = 0.24f
    private const val BRAKE_STRIP = 0.20f
    private const val BATTERY = 0.26f

    private const val WHEEL_HEIGHT = 0.20f
    /** 앞바퀴 윗변. 차체 앞쪽 띠(제동·조향)와 같은 높이다. */
    private const val FRONT_AXLE = 0.22f
    /** 뒷바퀴 아랫변. 차체 뒤쪽 끝에서 이만큼 띄운다. */
    private const val REAR_INSET = 0.06f

    /** 차체 옆으로 비우는 폭의 비율(양쪽 합). 바퀴가 누를 수 있는 좁기가 되도록 넓힌다. */
    private fun sideFraction(box: ZoneRect): Float {
        if (box.width <= 0f) return SIDE_FRACTION
        val needed = 2f * MIN_TOUCH_DP / box.width
        return maxOf(SIDE_FRACTION, needed).coerceAtMost(SIDE_MAX)
    }

    /** 차체 실루엣(차체만, 바퀴 제외). */
    fun bodyOutline(width: Float, height: Float): ZoneRect {
        val box = carBox(width, height)
        val side = sideFraction(box) / 2f
        return ZoneRect(
            box.left + box.width * side,
            box.top + box.height * BODY_TOP,
            box.right - box.width * side,
            box.top + box.height * BODY_BOTTOM,
        )
    }

    /** 보닛·차체 앞쪽 띠·배터리·실내 영역. */
    fun bodyZones(width: Float, height: Float): Map<Category, ZoneRect> {
        val body = bodyOutline(width, height)
        val bonnetBottom = body.top + body.height * BONNET
        val brakeBottom = bonnetBottom + body.height * BRAKE_STRIP
        val batteryBottom = brakeBottom + body.height * BATTERY
        return mapOf(
            Category.POWER to ZoneRect(body.left, body.top, body.right, bonnetBottom),
            Category.BRAKE_STEERING to ZoneRect(body.left, bonnetBottom, body.right, brakeBottom),
            Category.ELECTRIC to ZoneRect(body.left, brakeBottom, body.right, batteryBottom),
            Category.SAFETY to ZoneRect(body.left, batteryBottom, body.right, body.bottom),
        )
    }

    /** 바퀴 네 개. 앞바퀴는 보닛 높이에, 뒷바퀴는 차체 뒤쪽에 둔다. */
    fun wheelZones(width: Float, height: Float): Map<WheelArea, ZoneRect> {
        val box = carBox(width, height)
        val body = bodyOutline(width, height)
        val wheelHeight = body.height * WHEEL_HEIGHT
        val frontTop = body.top + body.height * FRONT_AXLE
        val rearBottom = body.bottom - body.height * REAR_INSET
        return mapOf(
            WheelArea.FL to ZoneRect(box.left, frontTop, body.left, frontTop + wheelHeight),
            WheelArea.FR to ZoneRect(body.right, frontTop, box.right, frontTop + wheelHeight),
            WheelArea.RL to ZoneRect(box.left, rearBottom - wheelHeight, body.left, rearBottom),
            WheelArea.RR to ZoneRect(body.right, rearBottom - wheelHeight, box.right, rearBottom),
        )
    }

    fun zones(width: Float, height: Float): List<DiagramZone> {
        val body = bodyZones(width, height)
        val wheels = wheelZones(width, height)
        return body.map { (category, rect) -> DiagramZone(category, null, rect) } +
            WheelArea.entries.map { wheel -> DiagramZone(Category.TIRE, wheel, wheels.getValue(wheel)) }
    }

    /** 누른 점이 어느 구역인가. 바퀴는 타이어 구역이다. 영역 밖이면 null. */
    fun zoneAt(width: Float, height: Float, x: Float, y: Float): Category? {
        wheelZones(width, height).forEach { (_, rect) ->
            if (rect.contains(x, y)) return Category.TIRE
        }
        bodyZones(width, height).forEach { (category, rect) ->
            if (rect.contains(x, y)) return category
        }
        return null
    }
}
