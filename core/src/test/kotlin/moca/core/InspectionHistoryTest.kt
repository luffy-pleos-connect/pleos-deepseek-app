package moca.core

import java.time.LocalDate

class InspectionHistoryTest {
    private val today = LocalDate.of(2026, 11, 11)

    @Test
    fun emptyLogKeepsSeasonStart() {
        Assert.eq(50, InspectionHistory.careScore("", today))
        Assert.isNull(InspectionHistory.latestLabel(""))
        Assert.eq(InspectionHistory.EMPTY_HOME_LABEL, InspectionHistory.homeLabel(""))
    }

    @Test
    fun oneInspectionRaisesCareAndShowsDate() {
        val line = InspectionHistory.line(today, VehicleSnapshot.normal())
        Assert.eq(52, InspectionHistory.careScore(line, today))
        Assert.eq("11월 11일", InspectionHistory.latestLabel(line))
        Assert.eq("11월 11일", InspectionHistory.homeLabel(line))
    }

    @Test
    fun roundTripKeepsOilAndTelltale() {
        val snapshot = VehicleSnapshot.normal().copy(
            engineOil = FluidLevel.LOW,
            lowTireWheels = setOf(WheelArea.FL),
            telltales = listOf(TelltaleState(Telltale.ABS, Color.RED)),
            unsupported = setOf(Item.TIRE),
        )
        val text = InspectionHistory.line(today, snapshot)
        val read = InspectionHistory.read(text, Profile.REAL).single()
        Assert.eq(FluidLevel.LOW, read.snapshot.engineOil)
        Assert.eq(setOf(WheelArea.FL), read.snapshot.lowTireWheels)
        Assert.eq(Telltale.ABS, read.snapshot.telltales.single().telltale)
        Assert.eq(setOf(Item.TIRE), read.snapshot.unsupported)
    }

    @Test
    fun twoDaysMakeTwoTrendRows() {
        val first = InspectionHistory.line(today.minusDays(1), VehicleSnapshot.normal())
        val second = InspectionHistory.line(today, VehicleSnapshot.normal())
        val rows = InspectionHistory.trendRows("$first\n$second", Profile.REAL)
        Assert.eq(2, rows.size)
        Assert.isTrue(rows[0].startsWith("11월 10일 "), rows[0])
        Assert.isTrue(rows[1].startsWith("11월 11일 "), rows[1])
    }

    // 2026-11-11 리뷰(docs/리뷰-20261111.md 결함 5): 오늘보다 뒤의 날짜가 마지막 점검일로 보였다.
    // 관리 점수(Care)는 오늘 이후 기록을 빼고 계산한다. 홈의 마지막 점검일도 같은 기준을 쓴다.

    @Test
    fun futureDateIsNotTheLatestInspection() {
        val text = InspectionHistory.line(today, VehicleSnapshot.normal()) + "\n" +
            InspectionHistory.line(today.plusDays(1), VehicleSnapshot.normal())
        Assert.eq("11월 11일", InspectionHistory.latestLabel(text, today))
        Assert.eq("11월 11일", InspectionHistory.homeLabel(text, today))
    }

    @Test
    fun onlyFutureDatesMeansNeverInspected() {
        val text = InspectionHistory.line(today.plusDays(3), VehicleSnapshot.normal())
        Assert.isNull(InspectionHistory.latestLabel(text, today))
        Assert.eq(InspectionHistory.EMPTY_HOME_LABEL, InspectionHistory.homeLabel(text, today))
    }
}
