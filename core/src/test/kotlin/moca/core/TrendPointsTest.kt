package moca.core

import java.time.LocalDate

// 계획 v9 의 5-3(상세 화면의 추이 그래프)에 쓸 숫자다.
// 하루에 점 하나(그날의 마지막 점검), 오래된 날부터, 최근 14일치. 점수는 기존 trendRows 와 같은 방법으로 구한다.
class TrendPointsTest {
    private val today = LocalDate.of(2026, 11, 20)
    private val abs = VehicleSnapshot.normal().copy(telltales = listOf(TelltaleState(Telltale.ABS, Color.RED)))
    private fun line(day: LocalDate, snapshot: VehicleSnapshot = VehicleSnapshot.normal()) =
        InspectionHistory.line(day, snapshot)

    @Test
    fun emptyLogHasNoPoints() {
        Assert.eq(emptyList<TrendPoint>(), InspectionHistory.trendPoints("", Profile.REAL, today))
    }

    @Test
    fun onePointPerDayMatchesTrendRows() {
        val days = listOf(today.minusDays(2), today.minusDays(1), today)
        val text = listOf(line(days[0], abs), line(days[1]), line(days[2])).joinToString("\n")
        val points = InspectionHistory.trendPoints(text, Profile.REAL, today)
        Assert.eq(days, points.map { it.date })
        val rows = InspectionHistory.trendRows(text, Profile.REAL)
        Assert.eq(rows, points.map { "${InspectionHistory.label(it.date)} ${it.overall ?: "—"}" })
        Assert.isTrue(points[0].overall!! < points[1].overall!!, "빨간 경고가 있던 날이 더 낮다")
    }

    @Test
    fun sameDayKeepsOnlyTheLastInspection() {
        val text = listOf(line(today, abs), line(today)).joinToString("\n")
        val points = InspectionHistory.trendPoints(text, Profile.REAL, today)
        Assert.eq(1, points.size)
        val lastRow = InspectionHistory.trendRows(text, Profile.REAL).last()
        Assert.eq(lastRow, "${InspectionHistory.label(today)} ${points[0].overall ?: "—"}")
    }

    @Test
    fun futureDatesAndBrokenLinesAreIgnored() {
        val text = listOf(line(today), "깨진 줄", line(today.plusDays(1))).joinToString("\n")
        Assert.eq(listOf(today), InspectionHistory.trendPoints(text, Profile.REAL, today).map { it.date })
    }

    @Test
    fun onlyTheLastFourteenDaysAreKept() {
        val text = (19 downTo 0).joinToString("\n") { line(today.minusDays(it.toLong())) }
        val points = InspectionHistory.trendPoints(text, Profile.REAL, today)
        Assert.eq(14, points.size)
        Assert.eq(today.minusDays(13), points.first().date)
        Assert.eq(today, points.last().date)
    }
}
