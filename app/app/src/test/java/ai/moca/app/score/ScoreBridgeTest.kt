package ai.moca.app.score

import ai.moca.app.vehicle.ActiveWarning
import ai.moca.app.vehicle.DemoScenario
import ai.moca.app.vehicle.ItemReading
import ai.moca.app.vehicle.PleosVehicleSource
import ai.moca.app.vehicle.WarningSummary
import moca.core.Category
import moca.core.WheelArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 평가기준.md §3 의 D3 기준값을 앱 경로(ScoreBridge)로 고정한다.
 * 기준: 동력 100 / 제동·조향 50 / 타이어 75 / 전기 100 / 안전 100,
 *       건강 85, 종합 75.4 → B · 75, A까지 5.
 */
class ScoreBridgeTest {

    private fun d3Readings(): Map<String, ItemReading> = mapOf(
        PleosVehicleSource.KEY_OIL_ENGINE to ItemReading(PleosVehicleSource.KEY_OIL_ENGINE, true, 0.0),
        PleosVehicleSource.KEY_BRAKE_FLUID to ItemReading(PleosVehicleSource.KEY_BRAKE_FLUID, true, 0.0),
        PleosVehicleSource.KEY_WASHER to ItemReading(PleosVehicleSource.KEY_WASHER, true, 0.0),
        PleosVehicleSource.KEY_POWER_DOWN to ItemReading(PleosVehicleSource.KEY_POWER_DOWN, true, 0.0),
        PleosVehicleSource.KEY_SOC to ItemReading(PleosVehicleSource.KEY_SOC, true, 62.0),
        PleosVehicleSource.KEY_TIRE_LOW to ItemReading(PleosVehicleSource.KEY_TIRE_LOW, true, 1.0),
        PleosVehicleSource.KEY_TELLTALES to ItemReading(PleosVehicleSource.KEY_TELLTALES, true, 2.0),
    )

    private fun d3Warnings(): WarningSummary = WarningSummary(
        redCount = 1,
        yellowCount = 1,
        total = 2,
        isDemo = true,
        active = listOf(ActiveWarning("ABS", 4), ActiveWarning("TPMS", 3)),
    )

    @Test
    fun `D3 카테고리 점수가 기준표와 같다`() {
        val e = ScoreBridge.evaluate(d3Readings(), d3Warnings(), isDemo = true)
        assertEquals(100, e.categories[Category.POWER])
        assertEquals("ABS RED -50", 50, e.categories[Category.BRAKE_STEERING])
        assertEquals("TPMS YELLOW -25 가 저압 -20 보다 크다", 75, e.categories[Category.TIRE])
        assertEquals(100, e.categories[Category.ELECTRIC])
        assertEquals(100, e.categories[Category.SAFETY])
    }

    @Test
    fun `D3 건강과 등급과 표시 종합이 기준표와 같다`() {
        val e = ScoreBridge.evaluate(d3Readings(), d3Warnings(), isDemo = true)
        assertEquals(85.0, e.health!!, 0.001)
        assertEquals(75.4, e.overall!!, 0.001)
        assertEquals("B", e.grade!!.label)
        assertEquals(75, e.displayedOverall)
        assertEquals(5, e.pointsToNext)
    }

    @Test
    fun `ABS 는 제동에 들어가고 안전에는 들어가지 않는다`() {
        val e = ScoreBridge.evaluate(d3Readings(), d3Warnings(), isDemo = true)
        assertEquals("ABS RED 는 제동 -50", 50, e.categories[Category.BRAKE_STEERING])
        assertEquals("ABS 가 안전으로 새면 안 된다", 100, e.categories[Category.SAFETY])
    }

    @Test
    fun `못 읽은 값은 정상이 아니라 측정 불가가 된다`() {
        val readings = d3Readings().toMutableMap()
        readings[PleosVehicleSource.KEY_OIL_ENGINE] =
            ItemReading(PleosVehicleSource.KEY_OIL_ENGINE, true, null, "timeout")
        val e = ScoreBridge.evaluate(readings, d3Warnings(), isDemo = false)
        assertNull("오일을 못 읽었으므로 동력은 측정 불가", e.categories[Category.POWER])
    }

    @Test
    fun `미지원 항목이 많으면 등급이 없다`() {
        val readings = mapOf(
            PleosVehicleSource.KEY_OIL_ENGINE to
                ItemReading(PleosVehicleSource.KEY_OIL_ENGINE, false, null, "unsupported"),
            PleosVehicleSource.KEY_BRAKE_FLUID to
                ItemReading(PleosVehicleSource.KEY_BRAKE_FLUID, false, null, "unsupported"),
            PleosVehicleSource.KEY_SOC to ItemReading(PleosVehicleSource.KEY_SOC, true, 62.0),
            PleosVehicleSource.KEY_TIRE_LOW to ItemReading(PleosVehicleSource.KEY_TIRE_LOW, true, 0.0),
        )
        val e = ScoreBridge.evaluate(readings, WarningSummary.empty(), isDemo = false)
        assertNull("측정 카테고리 2개면 등급 없음", e.grade)
    }

    @Test
    fun `데모 D3 시나리오 readings 로도 같은 값이 나온다`() {
        val scenario = DemoScenario.D3
        val e = ScoreBridge.evaluate(scenario.readings(), scenario.warnings(), isDemo = true)
        assertEquals(75, e.displayedOverall)
        assertEquals("B", e.grade!!.label)
    }

    // 2026-11-11 리뷰(docs/리뷰-20261111.md 결함 6): 연료는 읽지 않는데 기본값 62.0 이 기록에 남았다.
    @Test
    fun `읽지 않은 연료는 값이 없다`() {
        val snapshot = ScoreBridge.toEngineSnapshot(d3Readings(), d3Warnings(), isDemo = false)
        assertNull("연료는 읽지 않으므로 기본값이 들어가면 안 된다", snapshot.fuelPercent)
        assertEquals(62.0, snapshot.batteryPercent!!, 0.0)
    }

    @Test
    fun `뒤오른쪽만 저압이면 그 바퀴만 기록된다`() {
        val readings = d3Readings().toMutableMap()
        readings[PleosVehicleSource.KEY_TIRE_LOW] =
            ItemReading(PleosVehicleSource.KEY_TIRE_LOW, true, 1.0, "RR")
        val snapshot = ScoreBridge.toEngineSnapshot(readings, WarningSummary.empty(), isDemo = false)
        assertEquals(setOf(WheelArea.RR), snapshot.lowTireWheels)
    }

    @Test
    fun `위치 없이 개수만 있으면 앞에서부터 고르지 않는다`() {
        val snapshot = ScoreBridge.toEngineSnapshot(d3Readings(), d3Warnings(), isDemo = true)
        assertEquals(emptySet<WheelArea>(), snapshot.lowTireWheels)
    }

    @Test
    fun `데모는 시나리오에 적힌 바퀴만 저압이다`() {
        fun wheels(scenario: DemoScenario) = ScoreBridge.toEngineSnapshot(
            scenario.readings(),
            scenario.warnings(),
            isDemo = true,
        ).lowTireWheels
        // 엔진 데모(Demo.kt)의 D2 는 앞왼쪽·뒤오른쪽이다. 손으로 쓴 옛 값(FL+FR)을 따르지 않는다.
        assertEquals(setOf(WheelArea.FL, WheelArea.RR), wheels(DemoScenario.D2))
        assertEquals(setOf(WheelArea.FL), wheels(DemoScenario.D3))
        assertEquals(setOf(WheelArea.FL), wheels(DemoScenario.D5))
        assertEquals(emptySet<WheelArea>(), wheels(DemoScenario.D0))
    }
}
