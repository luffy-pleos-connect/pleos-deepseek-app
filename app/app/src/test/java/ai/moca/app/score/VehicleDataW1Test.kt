package ai.moca.app.score

import ai.moca.app.vehicle.ActiveWarning
import ai.moca.app.vehicle.ItemReading
import ai.moca.app.vehicle.PleosVehicleSource
import ai.moca.app.vehicle.TelltaleTypeMap
import ai.moca.app.vehicle.WarningCounter
import ai.moca.app.vehicle.WarningSummary
import moca.core.Category
import moca.core.Color
import moca.core.FluidLevel
import moca.core.Item
import moca.core.Telltale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 개발 문서 W1(차량 데이터 바로잡기). 최종정리 2.2·2.3절의 값이 앱 경로(ScoreBridge)로 나오는지 고정한다.
 * lead 가 쓴 테스트다. 워커는 이 파일을 고치지 않는다.
 *
 * 읽기 값의 약속(ItemReading.value):
 *   오일·브레이크액·변속기오일  0 = 정상, 1 = 부족, 2 = 심각 부족, 3 = 과다. 센서 오류는 note = "sensor_error"
 *   워셔액·파워다운·에어백       0 = 아니요, 1 = 예
 *   연료·배터리                  % (0~100)
 *   누적 주행거리                 SDK 가 준 숫자 그대로(단위 확인 전). 점수에는 쓰지 않는다
 */
class VehicleDataW1Test {

    private fun r(key: String, value: Double?, supported: Boolean = true, note: String? = null) =
        key to ItemReading(key, supported, value, note)

    /** 전부 정상으로 읽힌 차. 필요한 항목만 덮어쓴다. */
    private fun readings(vararg override: Pair<String, ItemReading>): Map<String, ItemReading> = mapOf(
        r(PleosVehicleSource.KEY_OIL_ENGINE, 0.0),
        r(PleosVehicleSource.KEY_OIL_TRANSMISSION, 0.0),
        r(PleosVehicleSource.KEY_BRAKE_FLUID, 0.0),
        r(PleosVehicleSource.KEY_WASHER, 0.0),
        r(PleosVehicleSource.KEY_POWER_DOWN, 0.0),
        r(PleosVehicleSource.KEY_AIRBAG, 0.0),
        r(PleosVehicleSource.KEY_SOC, 80.0),
        r(PleosVehicleSource.KEY_FUEL, 62.0),
        r(PleosVehicleSource.KEY_TIRE_LOW, 0.0),
        r(PleosVehicleSource.KEY_TELLTALES, 0.0),
        r(PleosVehicleSource.KEY_ODOMETER, 12345.0),
    ) + override

    private val noWarnings = WarningSummary.empty(isDemo = false)

    private fun evaluate(items: Map<String, ItemReading>, warnings: WarningSummary = noWarnings) =
        ScoreBridge.evaluate(items, warnings, isDemo = false)

    private fun snapshot(items: Map<String, ItemReading>, warnings: WarningSummary = noWarnings) =
        ScoreBridge.toEngineSnapshot(items, warnings, isDemo = false)

    // ── B-01 오일 상태 ────────────────────────────────────────────────

    @Test
    fun `엔진오일 과다는 심각 부족이 아니라 과다로 계산된다`() {
        val items = readings(r(PleosVehicleSource.KEY_OIL_ENGINE, 3.0))
        assertEquals(FluidLevel.HIGH, snapshot(items).engineOil)
        assertEquals("과다는 -25", 75, evaluate(items).categories[Category.POWER])
    }

    @Test
    fun `오일 상태 네 가지가 최종정리 2_3절 감점과 같다`() {
        fun power(value: Double) = evaluate(readings(r(PleosVehicleSource.KEY_OIL_ENGINE, value))).categories[Category.POWER]
        assertEquals(100, power(0.0))
        assertEquals("부족 -40", 60, power(1.0))
        assertEquals("심각 부족 -50", 50, power(2.0))
        assertEquals("과다 -25", 75, power(3.0))
        val brake = evaluate(readings(r(PleosVehicleSource.KEY_BRAKE_FLUID, 3.0))).categories[Category.BRAKE_STEERING]
        assertEquals("브레이크액 과다 -25", 75, brake)
    }

    @Test
    fun `센서 오류는 정상이 아니라 오류로 넘긴다`() {
        val items = readings(r(PleosVehicleSource.KEY_OIL_ENGINE, null, note = "sensor_error"))
        assertEquals(FluidLevel.ERROR, snapshot(items).engineOil)
    }

    // ── 변속기오일 ────────────────────────────────────────────────────

    @Test
    fun `변속기오일을 읽으면 동력 점수에 들어간다`() {
        val items = readings(r(PleosVehicleSource.KEY_OIL_TRANSMISSION, 1.0))
        assertEquals(FluidLevel.LOW, snapshot(items).transmissionOil)
        assertFalse(Item.TRANSMISSION_OIL in snapshot(items).unsupported)
        assertEquals("변속기오일 부족 -40", 60, evaluate(items).categories[Category.POWER])
    }

    @Test
    fun `변속기오일을 못 읽으면 측정 불가로 넘긴다`() {
        val items = readings() - PleosVehicleSource.KEY_OIL_TRANSMISSION
        assertTrue(Item.TRANSMISSION_OIL in snapshot(items).unsupported)
        assertEquals("엔진오일은 읽혔으므로 동력은 측정된다", 100, evaluate(items).categories[Category.POWER])
    }

    // ── B-02 연료 ─────────────────────────────────────────────────────

    @Test
    fun `연료를 읽으면 그 값이 넘어가고 10퍼센트 미만이면 감점된다`() {
        val items = readings(r(PleosVehicleSource.KEY_FUEL, 8.0))
        assertEquals(8.0, snapshot(items).fuelPercent!!, 0.001)
        assertEquals("연료 10% 미만 -10", 90, evaluate(items).categories[Category.ELECTRIC])
    }

    @Test
    fun `연료를 못 읽으면 62가 아니라 값 없음이다`() {
        val items = readings() - PleosVehicleSource.KEY_FUEL
        assertNull(snapshot(items).fuelPercent)
    }

    @Test
    fun `연료만 읽히는 차도 전기 구역이 측정된다`() {
        val items = readings(r(PleosVehicleSource.KEY_SOC, null, supported = false, note = "unsupported"))
        assertFalse("연료와 배터리 둘 다 못 읽을 때만 측정 불가", Item.ENERGY in snapshot(items).unsupported)
        assertNull(snapshot(items).batteryPercent)
        assertEquals(100, evaluate(items).categories[Category.ELECTRIC])
    }

    @Test
    fun `연료와 배터리를 둘 다 못 읽으면 잔량은 측정 불가다`() {
        val items = readings() - PleosVehicleSource.KEY_FUEL - PleosVehicleSource.KEY_SOC
        assertTrue(Item.ENERGY in snapshot(items).unsupported)
    }

    // ── 에어백 ────────────────────────────────────────────────────────

    @Test
    fun `에어백이 전개됐으면 안전 구역이 50점 깎인다`() {
        val items = readings(r(PleosVehicleSource.KEY_AIRBAG, 1.0))
        assertTrue(snapshot(items).airbagDeployed)
        assertEquals(50, evaluate(items).categories[Category.SAFETY])
    }

    @Test
    fun `에어백을 못 읽으면 측정 불가로 넘긴다`() {
        val items = readings() - PleosVehicleSource.KEY_AIRBAG
        assertTrue(Item.AIRBAG in snapshot(items).unsupported)
        assertFalse(snapshot(items).airbagDeployed)
    }

    // ── 파워다운 ──────────────────────────────────────────────────────

    @Test
    fun `파워다운 경고가 켜져 있으면 파워다운 노랑 경고등으로 넘긴다`() {
        val items = readings(r(PleosVehicleSource.KEY_POWER_DOWN, 1.0))
        val telltales = snapshot(items).telltales
        assertEquals(1, telltales.size)
        assertEquals(Telltale.POWER_DOWN, telltales[0].telltale)
        assertEquals(Color.YELLOW, telltales[0].color)
        assertEquals("파워다운 -25 (최종정리 2.3절)", 75, evaluate(items).categories[Category.ELECTRIC])
    }

    @Test
    fun `파워다운 경고등이 이미 와 있으면 두 번 넣지 않는다`() {
        val items = readings(r(PleosVehicleSource.KEY_POWER_DOWN, 1.0))
        val warnings = WarningSummary(0, 1, 1, false, listOf(ActiveWarning("POWER_DOWN", 3)))
        assertEquals(1, snapshot(items, warnings).telltales.count { it.telltale == Telltale.POWER_DOWN })
        assertEquals(75, evaluate(items, warnings).categories[Category.ELECTRIC])
    }

    @Test
    fun `파워다운 경고가 꺼져 있으면 아무것도 더하지 않는다`() {
        assertTrue(snapshot(readings()).telltales.isEmpty())
    }

    // ── B-04 경고등 ───────────────────────────────────────────────────

    @Test
    fun `경고등의 깜빡임을 엔진에 그대로 넘긴다`() {
        val warnings = WarningSummary(1, 0, 1, false, listOf(ActiveWarning("ABS", 4, blinking = true)))
        val abs = snapshot(readings(), warnings).telltales.single()
        assertEquals(Telltale.ABS, abs.telltale)
        assertEquals(Color.RED, abs.color)
        assertTrue(abs.blinking)
    }

    @Test
    fun `경고등 종류 대응은 한 곳에 있고 열여덟 개가 모두 맞는다`() {
        val expected = mapOf(
            "ENGINE" to Telltale.ENGINE, "ENGINE_OIL_PRESSURE" to Telltale.ENGINE_OIL_PRESSURE,
            "ENGINE_TEMPERATURE" to Telltale.ENGINE_TEMPERATURE, "EV_SYSTEM" to Telltale.EV_SYSTEM,
            "HEV_SYSTEM" to Telltale.HEV_SYSTEM, "PARTICLE_FILTER" to Telltale.PARTICLE_FILTER,
            "ABS" to Telltale.ABS, "ESC" to Telltale.ESC, "MDPS" to Telltale.MDPS, "TPMS" to Telltale.TPMS,
            "LOW_VOLT_BATTERY" to Telltale.LOW_VOLT_BATTERY, "POWER_DOWN" to Telltale.POWER_DOWN,
            "AIRBAG" to Telltale.AIRBAG, "MASTER_WARNING" to Telltale.MASTER_WARNING,
            "LOW_FUEL" to Telltale.LOW_FUEL, "LOW_EV_BATTERY" to Telltale.LOW_EV_BATTERY,
            "SEAT_BELT" to Telltale.SEATBELT, "PASSENGER_AIRBAG_OFF" to Telltale.PASSENGER_AIRBAG_OFF,
        )
        for ((sdkName, telltale) in expected) assertEquals(sdkName, telltale, TelltaleTypeMap.toEngine(sdkName))
        assertEquals(Telltale.ABS, TelltaleTypeMap.toEngine("abs"))
        assertNull("대응 없는 종류는 버린다", TelltaleTypeMap.toEngine("HIGH_BEAM"))
        assertNull(TelltaleTypeMap.toEngine(""))
    }

    @Test
    fun `경고 배지는 감점 대상 경고등만 센다`() {
        val summary = WarningCounter.summarize(
            listOf(
                ActiveWarning("ABS", 4),          // 빨강, 감점 대상
                ActiveWarning("TPMS", 3),         // 노랑, 감점 대상
                ActiveWarning("SEAT_BELT", 4),    // 빨강이지만 감점하지 않는 종류
                ActiveWarning("LOW_FUEL", 3),     // 감점하지 않는 종류
                ActiveWarning("ENGINE", 1),       // 빨강·노랑이 아닌 색
                ActiveWarning("HIGH_BEAM", 2),    // 대응 없는 종류
            ),
            isDemo = false,
        )
        assertEquals(1, summary.redCount)
        assertEquals(1, summary.yellowCount)
        assertEquals("주의 n 의 n", 2, summary.total)
        assertEquals("엔진에 넘길 원본은 그대로 둔다", 6, summary.active.size)
    }

    // ── 누적 주행거리 ─────────────────────────────────────────────────

    @Test
    fun `누적 주행거리는 점수에 쓰지 않는다`() {
        val with = evaluate(readings())
        val without = evaluate(readings() - PleosVehicleSource.KEY_ODOMETER)
        assertEquals(with.overall, without.overall)
        assertEquals(with.categories, without.categories)
    }
}
