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
}
