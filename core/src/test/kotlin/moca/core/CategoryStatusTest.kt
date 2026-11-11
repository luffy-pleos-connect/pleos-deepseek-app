package moca.core

import java.time.LocalDate

// 계획 v9 의 5-1(상태를 색 칩과 낱말로)과 5-2(문제가 없으면 연속 점검 일수)의 규칙이다.
// 상태 경계는 지금 상태관리 화면이 쓰는 것과 같다: 100 이상 정상, 70 이상 주의, 그 아래 위험, 값 없음은 측정 불가.
class CategoryStatusTest {
    @Test
    fun scoreDecidesStatus() {
        Assert.eq(CategoryStatus.OK, CategoryStatus.of(100))
        Assert.eq(CategoryStatus.CAUTION, CategoryStatus.of(99))
        Assert.eq(CategoryStatus.CAUTION, CategoryStatus.of(70))
        Assert.eq(CategoryStatus.DANGER, CategoryStatus.of(69))
        Assert.eq(CategoryStatus.DANGER, CategoryStatus.of(0))
        Assert.eq(CategoryStatus.UNMEASURED, CategoryStatus.of(null))
    }

    @Test
    fun statusHasWord() {
        Assert.eq("정상", CategoryStatus.OK.label)
        Assert.eq("주의", CategoryStatus.CAUTION.label)
        Assert.eq("위험", CategoryStatus.DANGER.label)
        Assert.eq("측정 불가", CategoryStatus.UNMEASURED.label)
    }

    @Test
    fun demoRedWarningIsDangerAndYellowIsCaution() {
        val snapshot = VehicleSnapshot(
            telltales = listOf(TelltaleState(Telltale.ABS, Color.RED), TelltaleState(Telltale.TPMS, Color.YELLOW)),
        )
        val categories = Scoring.categories(snapshot)
        Assert.eq(CategoryStatus.DANGER, CategoryStatus.of(categories[Category.BRAKE_STEERING]))
        Assert.eq(CategoryStatus.CAUTION, CategoryStatus.of(categories[Category.TIRE]))
        Assert.eq(CategoryStatus.OK, CategoryStatus.of(categories[Category.POWER]))
    }

    private val today = LocalDate.of(2026, 11, 11)
    private fun log(vararg days: LocalDate) =
        days.joinToString("\n") { InspectionHistory.line(it, VehicleSnapshot.normal()) }

    @Test
    fun streakIsZeroWithoutTodaysInspection() {
        Assert.eq(0, InspectionHistory.streakDays("", today))
        Assert.eq(0, InspectionHistory.streakDays(log(today.minusDays(1)), today))
    }

    @Test
    fun streakCountsConsecutiveDaysEndingToday() {
        Assert.eq(1, InspectionHistory.streakDays(log(today), today))
        Assert.eq(3, InspectionHistory.streakDays(log(today.minusDays(2), today.minusDays(1), today), today))
    }

    @Test
    fun streakStopsAtAGap() {
        // 11/8 이 비어 있다. 11/9, 11/10, 11/11 만 센다.
        val text = log(today.minusDays(5), today.minusDays(4), today.minusDays(2), today.minusDays(1), today)
        Assert.eq(3, InspectionHistory.streakDays(text, today))
    }

    @Test
    fun streakCountsADayOnceAndIgnoresFutureAndBrokenLines() {
        val text = log(today, today, today.minusDays(1), today.plusDays(1)) + "\n깨진 줄"
        Assert.eq(2, InspectionHistory.streakDays(text, today))
    }
}
