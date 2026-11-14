package ai.moca.app.ui.model

import moca.core.Category
import moca.core.Color
import moca.core.Demo
import moca.core.FluidLevel
import moca.core.Item
import moca.core.Rules
import moca.core.Scoring
import moca.core.Telltale
import moca.core.TelltaleState
import moca.core.VehicleSnapshot
import moca.core.WheelArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 개발 문서 W7(상태관리): 감점 원인마다 붙는 해결방안. 최종정리 5장 2번·5번 표·12장.
 * lead 가 쓴 테스트다. 워커는 이 파일을 고치지 않는다. 문구는 strings.xml 에 두고, 여기서는 어떤 문구·단추가 붙는지만 정한다.
 *
 * 약속(ai.moca.app.ui.model):
 *   enum class SolutionKind { REPAIR_SHOP, BUY, SELF }          정비소 방문 / 용품 구매 / 직접 해결
 *   enum class SolutionButton { FIND_REPAIR, QR_INFLATOR, QR_WASHER, GAS_STATION, CHARGING_STATION }
 *   enum class SolutionText { WARNING_RED, WARNING_YELLOW, ENGINE_OIL_LOW, ENGINE_OIL_HIGH, TRANSMISSION_OIL, BRAKE_FLUID,
 *                             AIRBAG, TIRE_LOW, WASHER, FUEL_LOW, BATTERY_LOW, ENERGY_BOTH_LOW }
 *   data class Solution(val group: String, val label: String, val points: Int, val category: Category,
 *                       val kinds: List<SolutionKind>, val text: SolutionText, val stopFirst: Boolean, val buttons: List<SolutionButton>)
 *   object Solutions {
 *       fun of(snapshot: VehicleSnapshot): List<Solution>                        Rules.deductions 순서 그대로, 감점 원인마다 하나
 *       fun of(snapshot: VehicleSnapshot, category: Category): List<Solution>    그 구역 것만, 감점 큰 순
 *   }
 *   object StatusOrder { fun of(evaluation: moca.core.Evaluation): List<Category> }   점수 낮은 구역 먼저, 같으면 구역 순서, 측정 불가는 맨 뒤
 */
class SolutionsTest {

    private fun only(snapshot: VehicleSnapshot): Solution = Solutions.of(snapshot).single()
    private val normal = VehicleSnapshot.normal()

    @Test
    fun `데모 D3 는 ABS 가 정비소 찾기이고 타이어는 공기 주입기 QR 과 정비소 찾기다`() {
        val d3 = Demo.snapshotOf("D3")
        val abs = Solutions.of(d3, Category.BRAKE_STEERING).single()
        assertEquals("ABS 경고등", abs.label)
        assertEquals(50, abs.points)
        assertEquals(listOf(SolutionKind.REPAIR_SHOP), abs.kinds)
        assertEquals(SolutionText.WARNING_RED, abs.text)
        assertTrue("빨간 경고등은 정차 안내를 먼저 보여 준다", abs.stopFirst)
        assertEquals(listOf(SolutionButton.FIND_REPAIR), abs.buttons)

        val tire = Solutions.of(d3, Category.TIRE).single()
        assertEquals(25, tire.points)
        assertEquals(listOf(SolutionKind.BUY, SolutionKind.REPAIR_SHOP), tire.kinds)
        assertEquals(SolutionText.TIRE_LOW, tire.text)
        assertFalse(tire.stopFirst)
        assertEquals(listOf(SolutionButton.QR_INFLATOR, SolutionButton.FIND_REPAIR), tire.buttons)
        assertTrue(Solutions.of(d3, Category.POWER).isEmpty())
        assertEquals(2, Solutions.of(d3).size)
    }

    @Test
    fun `저압 바퀴 없이 타이어 경고등만 켜졌으면 경고등으로 안내한다`() {
        val s = only(normal.copy(telltales = listOf(TelltaleState(Telltale.TPMS, Color.YELLOW))))
        assertEquals(SolutionText.WARNING_YELLOW, s.text)
        assertEquals(listOf(SolutionKind.REPAIR_SHOP), s.kinds)
        assertEquals(listOf(SolutionButton.FIND_REPAIR), s.buttons)
    }

    @Test
    fun `엔진오일 부족과 심각 부족은 교체 문구이고 QR 이 없다`() {
        for (level in listOf(FluidLevel.LOW, FluidLevel.CRITICALLY_LOW)) {
            val s = only(normal.copy(engineOil = level))
            assertEquals(SolutionText.ENGINE_OIL_LOW, s.text)
            assertEquals(listOf(SolutionKind.REPAIR_SHOP), s.kinds)
            assertEquals(listOf(SolutionButton.FIND_REPAIR), s.buttons)
        }
        assertEquals(SolutionText.ENGINE_OIL_LOW, only(Demo.snapshotOf("D1")).text)
    }

    @Test
    fun `엔진오일 과다는 교체가 아니라 점검 문구다`() {
        val s = only(normal.copy(engineOil = FluidLevel.HIGH))
        assertEquals(SolutionText.ENGINE_OIL_HIGH, s.text)
        assertEquals(25, s.points)
        assertEquals(listOf(SolutionButton.FIND_REPAIR), s.buttons)
    }

    @Test
    fun `변속기오일 브레이크액 에어백 파워다운은 정비소 찾기다`() {
        val cases = listOf(
            normal.copy(transmissionOil = FluidLevel.LOW) to SolutionText.TRANSMISSION_OIL,
            normal.copy(transmissionOil = FluidLevel.HIGH) to SolutionText.TRANSMISSION_OIL,
            normal.copy(brakeFluid = FluidLevel.CRITICALLY_LOW) to SolutionText.BRAKE_FLUID,
            normal.copy(airbagDeployed = true) to SolutionText.AIRBAG,
            normal.copy(telltales = listOf(TelltaleState(Telltale.POWER_DOWN, Color.YELLOW))) to SolutionText.WARNING_YELLOW,
        )
        for ((snapshot, text) in cases) {
            val s = only(snapshot)
            assertEquals(text, s.text)
            assertEquals(listOf(SolutionKind.REPAIR_SHOP), s.kinds)
            assertEquals(listOf(SolutionButton.FIND_REPAIR), s.buttons)
        }
    }

    @Test
    fun `노란 경고등은 정차 안내를 앞세우지 않는다`() {
        val s = only(normal.copy(telltales = listOf(TelltaleState(Telltale.ESC, Color.YELLOW))))
        assertEquals(SolutionText.WARNING_YELLOW, s.text)
        assertFalse(s.stopFirst)
    }

    @Test
    fun `타이어 저압은 용품 구매와 정비소 방문 둘 다다`() {
        val s = only(normal.copy(lowTireWheels = setOf(WheelArea.FL, WheelArea.RR)))
        assertEquals(40, s.points)
        assertEquals(listOf(SolutionKind.BUY, SolutionKind.REPAIR_SHOP), s.kinds)
        assertEquals(listOf(SolutionButton.QR_INFLATOR, SolutionButton.FIND_REPAIR), s.buttons)
    }

    @Test
    fun `워셔액 부족은 워셔액 QR 만 보여 준다`() {
        val s = only(Demo.snapshotOf("D7"))
        assertEquals(SolutionText.WASHER, s.text)
        assertEquals(listOf(SolutionKind.BUY), s.kinds)
        assertEquals(listOf(SolutionButton.QR_WASHER), s.buttons)
    }

    @Test
    fun `배터리가 10퍼센트 미만이면 충전소이고 연료면 주유소다`() {
        val battery = only(Demo.snapshotOf("D4"))
        assertEquals(SolutionText.BATTERY_LOW, battery.text)
        assertEquals(listOf(SolutionKind.SELF), battery.kinds)
        assertEquals(listOf(SolutionButton.CHARGING_STATION), battery.buttons)

        val fuel = only(normal.copy(fuelPercent = 5.0, batteryPercent = null))
        assertEquals(SolutionText.FUEL_LOW, fuel.text)
        assertEquals(listOf(SolutionButton.GAS_STATION), fuel.buttons)

        val both = only(normal.copy(fuelPercent = 5.0, batteryPercent = 5.0))
        assertEquals(SolutionText.ENERGY_BOTH_LOW, both.text)
        assertEquals(listOf(SolutionButton.GAS_STATION, SolutionButton.CHARGING_STATION), both.buttons)
    }

    @Test
    fun `정상인 차는 해결할 항목이 없다`() {
        assertTrue(Solutions.of(Demo.snapshotOf("D0")).isEmpty())
    }

    @Test
    fun `한 구역에 감점이 여럿이면 큰 것부터 준다`() {
        val snapshot = normal.copy(
            washerFluidLow = true,
            telltales = listOf(TelltaleState(Telltale.MASTER_WARNING, Color.YELLOW)),
            airbagDeployed = true,
        )
        assertEquals(listOf(50, 25, 10), Solutions.of(snapshot, Category.SAFETY).map { it.points })
    }

    @Test
    fun `구역은 점수가 낮은 것부터 놓고 측정 불가는 맨 뒤다`() {
        fun order(snapshot: VehicleSnapshot) = StatusOrder.of(Scoring.evaluate(snapshot, 53, Rules.hasRed(snapshot)))
        assertEquals(
            listOf(Category.BRAKE_STEERING, Category.TIRE, Category.POWER, Category.ELECTRIC, Category.SAFETY),
            order(Demo.snapshotOf("D3")),
        )
        assertEquals(Category.entries.toList(), order(Demo.snapshotOf("D0")))
        assertEquals(Category.TIRE, order(Demo.snapshotOf("D6")).last())
        val noTire = Demo.snapshotOf("D1").copy(unsupported = setOf(Item.TIRE))
        assertEquals(listOf(Category.POWER, Category.BRAKE_STEERING, Category.ELECTRIC, Category.SAFETY, Category.TIRE), order(noTire))
    }
}
