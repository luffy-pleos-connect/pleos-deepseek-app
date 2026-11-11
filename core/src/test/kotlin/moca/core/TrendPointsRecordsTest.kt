package moca.core

import java.time.LocalDate

// 데모 상세의 추이는 저장 한 줄이 아니라 Demo.recordsOf 로 그린다.
class TrendPointsRecordsTest {
    @Test
    fun demoD3PointsEndTodayAndMatchEvaluation() {
        val today = LocalDate.of(2026, 11, 11)
        val points = InspectionHistory.trendPoints(Demo.recordsOf("D3", today), Profile.DEMO, today)
        Assert.isTrue(points.size >= 2, "점이 2개 이상")
        Assert.eq(today, points.last().date)
        val expected = Demo.evaluateOf("D3").displayedOverall
        Assert.eq(expected, points.last().overall, "마지막 점수")
    }
}
