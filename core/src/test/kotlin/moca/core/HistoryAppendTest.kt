package moca.core

import java.time.LocalDate

// 리뷰(docs/리뷰-20261111.md) 결함 4: 기록을 줄 수(60줄)로 자르면 이번 달의 점검 날이 사라져 관리 점수가 내려간다.
// 고친 규칙: 줄 수로 자르지 않는다. 날짜로 자른다. 지난달 1일보다 앞선 줄만 버린다
// (관리 점수는 이번 달, 지난 시즌 등급은 지난달 기록만 쓴다).
class HistoryAppendTest {
    private val today = LocalDate.of(2026, 11, 7)
    private fun line(day: LocalDate) = InspectionHistory.line(day, VehicleSnapshot.normal())

    @Test
    fun appendAddsTheLineAtTheEnd() {
        val text = InspectionHistory.append("", line(today), today)
        Assert.eq(line(today), text)
        val two = InspectionHistory.append(text, line(today), today)
        Assert.eq(listOf(line(today), line(today)), two.lines())
    }

    @Test
    fun manySameDayLinesDoNotLowerCare() {
        // 리뷰의 실측과 같은 경우: 11-01 ~ 11-07 하루 한 줄(관리 69) 뒤에 11-07 줄을 54번 더 붙인다.
        var text = ""
        for (d in 1..7) text = InspectionHistory.append(text, line(LocalDate.of(2026, 11, d)), today)
        Assert.eq(69, InspectionHistory.careScore(text, today))
        repeat(54) { text = InspectionHistory.append(text, line(today), today) }
        Assert.eq(69, InspectionHistory.careScore(text, today))
        Assert.eq(7, InspectionHistory.streakDays(text, today))
    }

    @Test
    fun linesOlderThanLastMonthAreDropped() {
        val old = line(LocalDate.of(2026, 9, 30))
        val lastMonth = line(LocalDate.of(2026, 10, 1))
        val text = InspectionHistory.append(old + "\n" + lastMonth, line(today), today)
        Assert.eq(listOf(lastMonth, line(today)), text.lines())
    }

    @Test
    fun blankAndBrokenLinesAreDropped() {
        val text = InspectionHistory.append("\n깨진 줄\n" + line(today.minusDays(1)) + "\n\n", line(today), today)
        Assert.eq(listOf(line(today.minusDays(1)), line(today)), text.lines())
    }
}
