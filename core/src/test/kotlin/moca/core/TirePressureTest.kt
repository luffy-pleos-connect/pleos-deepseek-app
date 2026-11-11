package moca.core

class TirePressureTest {
    @Test
    fun pressureUnderThresholdIsLow() {
        val count = TirePressure.lowCount(
            listOf(160f to 180f, 200f to 180f, 179.9f to 180f),
        )
        Assert.eq(2, count)
    }

    @Test
    fun thresholdWithoutPressureIsNotLow() {
        Assert.isNull(TirePressure.lowCount(listOf(null to 180f, null to 200f)))
    }

    @Test
    fun equalPressureIsNotLow() {
        Assert.eq(0, TirePressure.lowCount(listOf(180f to 180f)))
    }

    // 아래 넷은 2026-11-11 리뷰(docs/리뷰-20261111.md 결함 1·2)에서 나온 규칙이다.
    // 못 읽은 바퀴를 「저압 아님」으로 세지 않는다.
    // 저압인 바퀴가 하나라도 확인되면 그 개수를 돌려준다(확인된 경고를 숨기지 않는다).
    // 저압이 확인되지 않았는데 못 읽은 바퀴가 하나라도 있으면 null(측정 불가)이다.

    @Test
    fun partlyUnreadWithoutLowIsUnmeasured() {
        Assert.isNull(TirePressure.lowCount(listOf(200f to 180f, null to 180f, 200f to null, null to null)))
        Assert.isNull(TirePressure.lowCount(listOf(200f to 180f, null to 180f)))
    }

    @Test
    fun partlyUnreadWithLowKeepsTheLowCount() {
        Assert.eq(1, TirePressure.lowCount(listOf(100f to 180f, null to 180f, null to null, 200f to 180f)))
    }

    @Test
    fun nanIsUnread() {
        Assert.isNull(TirePressure.lowCount(listOf(Float.NaN to 180f)))
        Assert.isNull(TirePressure.lowCount(listOf(200f to Float.NaN, 200f to 180f)))
        Assert.eq(1, TirePressure.lowCount(listOf(100f to 180f, Float.NaN to 180f)))
    }

    @Test
    fun emptyListIsUnmeasured() {
        Assert.isNull(TirePressure.lowCount(emptyList()))
    }

    @Test
    fun lowAreasNamesTheLowWheels() {
        Assert.eq(
            setOf(WheelArea.FL, WheelArea.RL),
            TirePressure.lowAreas(
                mapOf(
                    WheelArea.FL to (160f to 180f),
                    WheelArea.FR to (200f to 180f),
                    WheelArea.RL to (179.9f to 180f),
                ),
            ),
        )
    }

    @Test
    fun lowAreasEqualPressureIsNotLow() {
        Assert.eq(
            emptySet<WheelArea>(),
            TirePressure.lowAreas(mapOf(WheelArea.FL to (180f to 180f))),
        )
    }

    @Test
    fun lowAreasUnreadWithoutLowIsNull() {
        Assert.isNull(
            TirePressure.lowAreas(
                mapOf(
                    WheelArea.FL to (200f to 180f),
                    WheelArea.FR to (null to 180f),
                ),
            ),
        )
    }

    @Test
    fun lowAreasUnreadWithLowKeepsThoseWheels() {
        Assert.eq(
            setOf(WheelArea.FL),
            TirePressure.lowAreas(
                mapOf(
                    WheelArea.FL to (100f to 180f),
                    WheelArea.FR to (null to 180f),
                    WheelArea.RL to (null to null),
                    WheelArea.RR to (200f to 180f),
                ),
            ),
        )
    }

    @Test
    fun lowAreasNanIsUnread() {
        Assert.isNull(TirePressure.lowAreas(mapOf(WheelArea.FL to (Float.NaN to 180f))))
        Assert.isNull(
            TirePressure.lowAreas(
                mapOf(
                    WheelArea.FL to (200f to Float.NaN),
                    WheelArea.FR to (200f to 180f),
                ),
            ),
        )
        Assert.eq(
            setOf(WheelArea.FL),
            TirePressure.lowAreas(
                mapOf(
                    WheelArea.FL to (100f to 180f),
                    WheelArea.FR to (Float.NaN to 180f),
                ),
            ),
        )
    }

    @Test
    fun lowAreasEmptyIsNull() {
        Assert.isNull(TirePressure.lowAreas(emptyMap()))
    }
}
