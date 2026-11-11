package moca.core

/**
 * 바퀴 하나의 현재 압력과 저압 임계값을 비교한다.
 * `getLowTirePressure` 는 저압 여부가 아니라 임계값이다.
 * 둘 중 하나라도 없으면 그 바퀴는 세지 않는다.
 * 한 바퀴도 둘 다 읽지 못하면 null (이번에 측정 못 함).
 */
object TirePressure {
    fun lowCount(pressureAndThreshold: List<Pair<Float?, Float?>>): Int? {
        var seen = 0
        var low = 0
        for ((pressure, threshold) in pressureAndThreshold) {
            if (pressure == null || threshold == null) continue
            seen++
            if (pressure < threshold) low++
        }
        return if (seen == 0) null else low
    }
}
