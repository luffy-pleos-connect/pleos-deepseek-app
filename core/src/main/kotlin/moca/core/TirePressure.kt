package moca.core

/**
 * 바퀴 하나의 현재 압력과 저압 임계값을 비교한다.
 * `getLowTirePressure` 는 저압 여부가 아니라 임계값이다.
 * 압력이나 임계값이 없거나 NaN 이면 그 바퀴는 「못 읽은 바퀴」다.
 * 읽은 바퀴 가운데 저압이 하나라도 있으면 그 개수를 돌려준다(못 읽은 바퀴가 있어도).
 * 저압이 없는데 못 읽은 바퀴가 있으면 null (이번에 측정 못 함).
 * 목록이 비어 있으면 null.
 */
object TirePressure {
    fun lowCount(pressureAndThreshold: List<Pair<Float?, Float?>>): Int? {
        if (pressureAndThreshold.isEmpty()) return null
        var unread = 0
        var low = 0
        for ((pressure, threshold) in pressureAndThreshold) {
            if (pressure == null || threshold == null || pressure.isNaN() || threshold.isNaN()) {
                unread++
                continue
            }
            if (pressure < threshold) low++
        }
        return when {
            low > 0 -> low
            unread > 0 -> null
            else -> 0
        }
    }

    /**
     * [lowCount] 와 같은 규칙이다.
     * 확인된 저압이 있으면 그 바퀴들, 저압이 없는데 못 읽은 바퀴가 있으면 null, 비어 있으면 null.
     */
    fun lowAreas(readings: Map<WheelArea, Pair<Float?, Float?>>): Set<WheelArea>? {
        if (readings.isEmpty()) return null
        var unread = 0
        val low = mutableSetOf<WheelArea>()
        for ((area, pair) in readings) {
            val (pressure, threshold) = pair
            if (pressure == null || threshold == null || pressure.isNaN() || threshold.isNaN()) {
                unread++
                continue
            }
            if (pressure < threshold) low += area
        }
        return when {
            low.isNotEmpty() -> low
            unread > 0 -> null
            else -> emptySet()
        }
    }
}
