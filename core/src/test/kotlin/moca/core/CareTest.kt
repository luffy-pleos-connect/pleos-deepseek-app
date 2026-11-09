package moca.core

class CareTest {

    private val real = Profile.REAL

    private fun care(records: List<Inspection>, today: String, profile: Profile = real): Int =
        Care.evaluate(records, profile, Fx.day(today)).score

    private fun normal(date: String, profile: Profile = real) = Fx.insp(Fx.day(date).toString(), VehicleSnapshot.normal(), profile)

    @Test
    fun e1FirstDayCareIs52() {
        Assert.eq(52, care(listOf(normal("2026-11-05")), "2026-11-05"))
    }

    @Test
    fun sameDayTwiceOnlyCountsOnce() {
        val records = listOf(
            Fx.insp("2026-11-05", VehicleSnapshot.normal(), seq = 0),
            Fx.insp("2026-11-05", VehicleSnapshot.normal(), seq = 1),
        )
        Assert.eq(52, care(records, "2026-11-05"))
    }

    @Test
    fun sevenConsecutiveDaysGiveStreakBonus() {
        Assert.eq(69, care(Fx.consecutive("2026-11-01", 7), "2026-11-07"))
    }

    @Test
    fun streakBonusAtSixDaysIsNotYetGiven() {
        Assert.eq(62, care(Fx.consecutive("2026-11-01", 6), "2026-11-06"))
    }

    @Test
    fun fourteenConsecutiveDaysGiveTwoStreakBonuses() {
        Assert.eq(88, care(Fx.consecutive("2026-11-01", 14), "2026-11-14"))
    }

    @Test
    fun e4SevenDaysThenIdleUntilDay30Is60() {
        Assert.eq(60, care(Fx.consecutive("2026-11-01", 7), "2026-11-30"))
    }

    @Test
    fun decayDoesNotStartOnDay14() {
        Assert.eq(52, care(listOf(normal("2026-11-01")), "2026-11-15"))
    }

    @Test
    fun decayStartsOnDay15() {
        Assert.eq(51, care(listOf(normal("2026-11-01")), "2026-11-16"))
    }

    @Test
    fun oneResolvedItemGivesFive() {
        val bad = Fx.insp("2026-11-01", VehicleSnapshot.normal().copy(engineOil = FluidLevel.LOW))
        val records = listOf(bad, normal("2026-11-02"))
        Assert.eq(59, care(records, "2026-11-02"))
    }

    @Test
    fun resolvedBonusIsCappedAtTenPerDay() {
        val bad = Fx.insp(
            "2026-11-01",
            VehicleSnapshot.normal().copy(
                engineOil = FluidLevel.LOW,
                washerFluidLow = true,
                telltales = listOf(Fx.red(Telltale.ABS)),
            ),
        )
        val records = listOf(bad, normal("2026-11-02"))
        Assert.eq(64, care(records, "2026-11-02"))
    }

    @Test
    fun unchangedDeductionsGiveNoResolvedBonus() {
        val bad = Fx.insp("2026-11-01", VehicleSnapshot.normal().copy(engineOil = FluidLevel.LOW))
        val same = Fx.insp("2026-11-02", VehicleSnapshot.normal().copy(engineOil = FluidLevel.LOW))
        Assert.eq(54, care(listOf(bad, same), "2026-11-02"))
    }

    @Test
    fun profilesAreSeparated() {
        val records = listOf(
            normal("2026-11-01", Profile.REAL),
            normal("2026-11-01", Profile.DEMO),
            normal("2026-11-02", Profile.REAL),
        )
        Assert.eq(54, care(records, "2026-11-02", Profile.REAL))
        Assert.eq(52, care(records, "2026-11-02", Profile.DEMO))
    }

    @Test
    fun scoreNeverDropsBelowZero() {
        Assert.eq(0, care(listOf(normal("2026-01-01", Profile.DEMO)), "2026-11-30", Profile.DEMO))
    }

    @Test
    fun scoreNeverRisesAboveOneHundred() {
        Assert.eq(100, care(Fx.consecutive("2026-11-01", 60), "2026-11-30"))
    }

    @Test
    fun futureRecordsAreIgnored() {
        val records = listOf(normal("2026-11-05"), normal("2026-11-08"))
        Assert.eq(52, care(records, "2026-11-05"))
    }

    @Test
    fun seasonStartsAtFiftyOnFirstOfMonth() {
        val november = Fx.consecutive("2026-11-01", 14)
        Assert.eq(52, care(november + normal("2026-12-01"), "2026-12-01"))
        Assert.eq(50, care(november, "2026-12-01"), "12월 1일 기록이 없으면 50에서 감쇠만")
    }

    @Test
    fun previousMonthRecordsDoNotAddToThisSeason() {
        val records = Fx.consecutive("2026-11-20", 10)
        Assert.eq(50, care(records, "2026-12-02"), "12월 기록 없음")
    }

    @Test
    fun e6SevenConsecutiveFromSeasonStartIsS() {
        val records = Fx.consecutive("2026-11-01", 7)
        val result = Care.evaluate(records, real, Fx.day("2026-11-07"))
        val evaluation = Scoring.evaluate(VehicleSnapshot.normal(), result.score)
        Assert.eq(69, result.score)
        Assert.near(90.7, evaluation.overall!!)
        Assert.eq(Grade.S, evaluation.grade)
    }

    @Test
    fun e6FourteenConsecutiveFromSeasonStartIsSPlus() {
        val records = Fx.consecutive("2026-11-01", 14)
        val result = Care.evaluate(records, real, Fx.day("2026-11-14"))
        val evaluation = Scoring.evaluate(VehicleSnapshot.normal(), result.score)
        Assert.eq(88, result.score)
        Assert.near(96.4, evaluation.overall!!)
        Assert.eq(Grade.S_PLUS, evaluation.grade)
    }

    @Test
    fun e7NewMonthResetsCareAndKeepsPreviousGrade() {
        val records = Fx.consecutive("2026-11-17", 14)
        val lastDay = Care.evaluate(records, real, Fx.day("2026-11-30"))
        Assert.eq(87, lastDay.score, "11/16 시즌 첫 점검 전 감쇠 1회")
        Assert.eq(Grade.S_PLUS, Care.previousSeasonGrade(records, java.time.YearMonth.of(2026, 11)))

        val dec = records + normal("2026-12-01")
        val result = Care.evaluate(dec, real, Fx.day("2026-12-01"))
        Assert.eq(52, result.score)
        Assert.eq(Grade.S_PLUS, result.previousSeasonGrade)
        Assert.eq("12월 시즌", result.seasonLabel)

        val evaluation = Scoring.evaluate(VehicleSnapshot.normal(), result.score)
        Assert.near(85.6, evaluation.overall!!)
        Assert.eq(Grade.A, evaluation.grade)
    }

    @Test
    fun monthBoundaryDayUsesNewSeason() {
        val records = listOf(
            normal("2026-11-29"),
            normal("2026-11-30"),
            normal("2026-12-01"),
            normal("2026-12-02"),
        )
        Assert.eq(52, care(records, "2026-12-01"))
        Assert.eq(54, care(records, "2026-12-02"))
    }

    @Test
    fun streakRestartsInsideNewSeason() {
        val records = Fx.consecutive("2026-11-29", 2) + Fx.consecutive("2026-12-01", 7)
        Assert.eq(62, care(records, "2026-12-06"))
        Assert.eq(69, care(records, "2026-12-07"))
    }

    @Test
    fun seasonWithNoInspectionDecaysFromSeasonStart() {
        Assert.eq(45, care(emptyList(), "2026-12-20"))
    }

    @Test
    fun resolvedBonusIsCreditedToTheCurrentSeason() {
        val novemberBad = Fx.insp("2026-11-30", VehicleSnapshot.normal().copy(engineOil = FluidLevel.LOW))
        val decemberGood = normal("2026-12-01")
        Assert.eq(57, care(listOf(novemberBad, decemberGood), "2026-12-01"))
    }

    @Test
    fun demoProfileHasNoSeason() {
        val result = Care.evaluate(Fx.consecutive("2026-11-01", 3, Profile.DEMO), Profile.DEMO, Fx.day("2026-11-03"))
        Assert.isNull(result.seasonLabel)
        Assert.isNull(result.previousSeasonGrade)
        Assert.eq(56, result.score)
    }

    @Test
    fun demoCareDoesNotDependOnCalendarMonth() {
        val records = Fx.consecutive("2026-11-29", 3, Profile.DEMO)
        Assert.eq(56, Care.evaluate(records, Profile.DEMO, Fx.day("2026-12-01")).score)
        Assert.eq(54, Care.evaluate(records, Profile.DEMO, Fx.day("2026-11-30")).score)
    }

    @Test
    fun tenDayGapBreaksStreak() {
        val records = listOf(normal("2026-11-01")) + Fx.consecutive("2026-11-11", 6)
        Assert.eq(64, care(records, "2026-11-16"))
    }
}
