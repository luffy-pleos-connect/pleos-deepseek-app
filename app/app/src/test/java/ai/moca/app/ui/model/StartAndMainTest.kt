package ai.moca.app.ui.model

import ai.moca.app.vehicle.ActiveWarning
import ai.moca.app.vehicle.WarningSummary
import moca.core.Category
import moca.core.CategoryStatus
import moca.core.Color
import moca.core.Demo
import moca.core.Item
import moca.core.Rules
import moca.core.Scoring
import moca.core.Telltale
import moca.core.TelltaleState
import moca.core.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 개발 문서 W5(앱 시작 화면)·W6(메인 화면)에서 화면이 보여 줄 값을 만드는 규칙. 최종정리 3장 1~6번, 4장 1번, 12장.
 * lead 가 쓴 테스트다. 워커는 이 파일을 고치지 않는다. 화면(Fragment)은 이 값을 그리기만 한다.
 *
 * 약속(ai.moca.app.ui.model):
 *   data class WarningLine(val label: String, val red: Boolean)                 켜진 감점 대상 경고등 한 줄
 *   data class CategoryLine(val category: Category, val score: Int?, val status: CategoryStatus)
 *   data class ActionLine(val group: String, val label: String, val points: Int, val category: Category)
 *   enum class EnergyKind { FUEL, BATTERY }
 *   data class EnergyLine(val kind: EnergyKind, val percent: Int)
 *   data class StartSummary(
 *       val gradeLabel: String?, val overall: Int?,       등급이 없으면 둘 다 null
 *       val warnings: List<WarningLine>,                  ② 빨강 먼저, 그다음 엔진 Telltale 순서
 *       val categories: List<CategoryLine>,               ③ 구역 순서(동력 → 안전) 5개
 *       val actions: List<ActionLine>,                    ④ 감점 큰 순, 같으면 구역 순서, 최대 3개(같은 원인 묶음은 엔진이 이미 한 줄로 줄였다)
 *       val energy: List<EnergyLine>,                     ⑤ 읽힌 것만. 연료 먼저, 배터리 다음. 소수점 버림
 *       val unmeasured: Int,                              ⑥ 측정 불가 구역 수
 *       val hasRed: Boolean,
 *   )
 *   object StartSummaryBuilder { fun of(snapshot: VehicleSnapshot, evaluation: moca.core.Evaluation): StartSummary }
 *   object StartPolicy {
 *       const val AUTO_ADVANCE_MS = 60_000L
 *       fun autoAdvances(summary: StartSummary, driving: Boolean): Boolean      정차 중 빨간 경고등이 있으면 false. 주행 중이면 항상 true(바로 넘어간다)
 *       fun showsOnlyGradeAndWarnings(driving: Boolean): Boolean                주행 중에는 ①②만
 *       fun speaksStopSafely(summary: StartSummary, driving: Boolean): Boolean  주행 중이고 빨간 경고등이 있을 때만
 *   }
 *   object AgoLabel { fun of(atMillis: Long, nowMillis: Long): String }         «방금 점검 기준», «N분 전 점검 기준», «N시간 전 점검 기준», «N일 전 점검 기준»
 *   enum class BadgeTone { RED, YELLOW, NONE }
 *   data class WarningBadge(val text: String, val tone: BadgeTone)
 *   object WarningBadges { fun of(summary: WarningSummary): WarningBadge }      «주의 n» / «주의 없음». n 은 감점 대상 경고등 수
 */
class StartAndMainTest {

    private fun summaryOf(snapshot: VehicleSnapshot, care: Int = 53): StartSummary =
        StartSummaryBuilder.of(snapshot, Scoring.evaluate(snapshot, care, Rules.hasRed(snapshot)))

    // ── 앱 시작 화면 ①~⑥ ─────────────────────────────────────────────

    @Test
    fun `데모 D3 는 B 75 이고 ABS 가 빨간 경고등으로 먼저 나온다`() {
        val s = summaryOf(Demo.snapshotOf("D3"))
        assertEquals("B", s.gradeLabel)
        assertEquals(75, s.overall)
        assertEquals(listOf(WarningLine("ABS", red = true), WarningLine("타이어 공기압", red = false)), s.warnings)
        assertTrue(s.hasRed)
    }

    @Test
    fun `데모 D3 의 구역은 100 50 75 100 100 이다`() {
        val s = summaryOf(Demo.snapshotOf("D3"))
        assertEquals(Category.entries.toList(), s.categories.map { it.category })
        assertEquals(listOf(100, 50, 75, 100, 100), s.categories.map { it.score })
        assertEquals(
            listOf(CategoryStatus.OK, CategoryStatus.DANGER, CategoryStatus.CAUTION, CategoryStatus.OK, CategoryStatus.OK),
            s.categories.map { it.status },
        )
        assertEquals(0, s.unmeasured)
    }

    @Test
    fun `데모 D3 의 조치 항목은 ABS 경고등 50 과 타이어 공기압 경고등 25 두 줄이다`() {
        val s = summaryOf(Demo.snapshotOf("D3"))
        assertEquals(listOf("ABS 경고등" to 50, "타이어 공기압 경고등" to 25), s.actions.map { it.label to it.points })
        assertEquals(listOf(Category.BRAKE_STEERING, Category.TIRE), s.actions.map { it.category })
    }

    @Test
    fun `조치 항목은 감점이 큰 순서이고 같으면 구역 순서이며 세 개까지다`() {
        val snapshot = VehicleSnapshot.normal().copy(
            washerFluidLow = true,                                   // 안전 -10
            batteryPercent = 5.0,                                    // 전기 -10
            lowTireWheels = setOf(moca.core.WheelArea.FL),            // 타이어 -20
            telltales = listOf(
                TelltaleState(Telltale.AIRBAG, Color.RED),            // 안전 -50
                TelltaleState(Telltale.ENGINE, Color.RED),            // 동력 -50
            ),
        )
        val s = summaryOf(snapshot)
        assertEquals(3, s.actions.size)
        assertEquals(listOf(50, 50, 20), s.actions.map { it.points })
        assertEquals(listOf(Category.POWER, Category.SAFETY, Category.TIRE), s.actions.map { it.category })
    }

    @Test
    fun `정상인 차는 조치 항목과 경고등이 없다`() {
        val s = summaryOf(Demo.snapshotOf("D0"))
        assertEquals("A", s.gradeLabel)
        assertEquals(85, s.overall)
        assertTrue(s.actions.isEmpty())
        assertTrue(s.warnings.isEmpty())
        assertFalse(s.hasRed)
    }

    @Test
    fun `감점하지 않는 경고등과 빨강 노랑이 아닌 경고등은 보여 주지 않는다`() {
        val snapshot = VehicleSnapshot.normal().copy(
            telltales = listOf(
                TelltaleState(Telltale.SEATBELT, Color.RED),
                TelltaleState(Telltale.LOW_FUEL, Color.YELLOW),
                TelltaleState(Telltale.ENGINE, Color.GREEN),
                TelltaleState(Telltale.ESC, Color.YELLOW),
            ),
        )
        assertEquals(listOf(WarningLine("ESC", red = false)), summaryOf(snapshot).warnings)
    }

    @Test
    fun `잔량은 읽힌 것만 연료 먼저 보여 준다`() {
        assertEquals(listOf(EnergyLine(EnergyKind.FUEL, 62), EnergyLine(EnergyKind.BATTERY, 80)), summaryOf(Demo.snapshotOf("D0")).energy)
        val evOnly = VehicleSnapshot.normal().copy(fuelPercent = null, batteryPercent = 9.9)
        assertEquals(listOf(EnergyLine(EnergyKind.BATTERY, 9)), summaryOf(evOnly).energy)
        val none = VehicleSnapshot.normal().copy(fuelPercent = null, batteryPercent = null, unsupported = setOf(Item.ENERGY))
        assertTrue(summaryOf(none).energy.isEmpty())
    }

    @Test
    fun `측정 불가 구역 수를 센다`() {
        assertEquals(1, summaryOf(Demo.snapshotOf("D6")).unmeasured)
        assertNull(summaryOf(Demo.snapshotOf("D6")).categories.first { it.category == Category.TIRE }.score)
        assertEquals(CategoryStatus.UNMEASURED, summaryOf(Demo.snapshotOf("D6")).categories.first { it.category == Category.TIRE }.status)
    }

    @Test
    fun `측정된 구역이 세 개 미만이면 등급이 없다`() {
        val poor = VehicleSnapshot.normal().copy(
            unsupported = setOf(Item.ENGINE_OIL, Item.TRANSMISSION_OIL, Item.BRAKE_FLUID, Item.TIRE, Item.WASHER_FLUID, Item.AIRBAG),
        )
        val s = summaryOf(poor)
        assertNull(s.gradeLabel)
        assertNull(s.overall)
        assertTrue(s.unmeasured >= 3)
    }

    // ── 메인으로 넘어가는 규칙 (최종정리 3장 3·4번) ───────────────────

    @Test
    fun `정차 중 빨간 경고등이 있으면 자동으로 넘어가지 않는다`() {
        val d3 = summaryOf(Demo.snapshotOf("D3"))
        val d0 = summaryOf(Demo.snapshotOf("D0"))
        val d2 = summaryOf(Demo.snapshotOf("D2")) // 노랑만
        assertFalse(StartPolicy.autoAdvances(d3, driving = false))
        assertTrue(StartPolicy.autoAdvances(d0, driving = false))
        assertTrue(StartPolicy.autoAdvances(d2, driving = false))
        assertEquals(60_000L, StartPolicy.AUTO_ADVANCE_MS)
    }

    @Test
    fun `주행 중에는 등급과 경고등만 보이고 바로 넘어가며 빨강이면 정차 안내를 말한다`() {
        val d3 = summaryOf(Demo.snapshotOf("D3"))
        val d0 = summaryOf(Demo.snapshotOf("D0"))
        assertTrue(StartPolicy.showsOnlyGradeAndWarnings(driving = true))
        assertFalse(StartPolicy.showsOnlyGradeAndWarnings(driving = false))
        assertTrue(StartPolicy.autoAdvances(d3, driving = true))
        assertTrue(StartPolicy.speaksStopSafely(d3, driving = true))
        assertFalse(StartPolicy.speaksStopSafely(d0, driving = true))
        assertFalse("정차 중에는 화면으로 보여 주면 된다", StartPolicy.speaksStopSafely(d3, driving = false))
    }

    // ── 메인 화면 (최종정리 4장 1번, B-14) ────────────────────────────

    @Test
    fun `점검 시각은 얼마 전인지로 보여 준다`() {
        val now = 1_794_600_000_000L
        assertEquals("방금 점검 기준", AgoLabel.of(now, now))
        assertEquals("방금 점검 기준", AgoLabel.of(now - 59_000, now))
        assertEquals("1분 전 점검 기준", AgoLabel.of(now - 60_000, now))
        assertEquals("59분 전 점검 기준", AgoLabel.of(now - 59 * 60_000L - 59_000, now))
        assertEquals("1시간 전 점검 기준", AgoLabel.of(now - 60 * 60_000L, now))
        assertEquals("23시간 전 점검 기준", AgoLabel.of(now - 23 * 3_600_000L - 59 * 60_000L, now))
        assertEquals("1일 전 점검 기준", AgoLabel.of(now - 24 * 3_600_000L, now))
        assertEquals("시계가 뒤로 가도 음수를 보여 주지 않는다", "방금 점검 기준", AgoLabel.of(now + 5_000, now))
    }

    @Test
    fun `경고 배지는 감점 대상 수와 색을 알려 준다`() {
        val red = WarningSummary(1, 1, 2, isDemo = true, active = listOf(ActiveWarning("ABS", 4), ActiveWarning("TPMS", 3)))
        assertEquals(WarningBadge("주의 2", BadgeTone.RED), WarningBadges.of(red))
        val yellow = WarningSummary(0, 1, 1, isDemo = false, active = listOf(ActiveWarning("TPMS", 3)))
        assertEquals(WarningBadge("주의 1", BadgeTone.YELLOW), WarningBadges.of(yellow))
        assertEquals(WarningBadge("주의 없음", BadgeTone.NONE), WarningBadges.of(WarningSummary.empty()))
    }
}
