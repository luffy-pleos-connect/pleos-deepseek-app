package moca.core

class DemoHistoryTest {

    private fun careOf(id: String, today: java.time.LocalDate = Demo.ANCHOR): Int = Demo.careOf(id, today).score

    private fun evalOf(id: String, today: java.time.LocalDate = Demo.ANCHOR): Evaluation =
        Scoring.evaluate(Demo.snapshotOf(id), careOf(id, today))

    @Test
    fun demoHistoryIsSevenNormalRecordsEveryOtherDay() {
        val records = Demo.history()
        Assert.eq(7, records.size)
        Assert.eq(0, records.count { it.profile != Profile.DEMO })
        val expectedDates = listOf(40L, 38L, 36L, 34L, 32L, 30L, 28L).map { Demo.ANCHOR.minusDays(it) }
        Assert.eq(expectedDates, records.map { it.inspectedAt })
    }

    @Test
    fun demoTodayCareIs53() {
        for ((scenario, records) in Demo.scenarios()) {
            val score = Care.evaluate(records, Profile.DEMO, Demo.ANCHOR).score
            Assert.eq(53, score, scenario.id)
        }
    }

    @Test
    fun demoProfileHasNoSeason() {
        Assert.isNull(Demo.careOf("D0").seasonLabel)
        Assert.isNull(Demo.careOf("D0").previousSeasonGrade)
    }

    @Test
    fun d0NormalIsEightyFiveA() {
        val e = evalOf("D0")
        Assert.near(85.9, e.overall!!)
        Assert.eq(85, e.displayedOverall)
        Assert.eq(Grade.A, e.grade)
    }

    @Test
    fun d1EngineOilLowIsEightyA() {
        val e = evalOf("D1")
        Assert.near(80.3, e.overall!!)
        Assert.eq(80, e.displayedOverall)
        Assert.eq(Grade.A, e.grade)
        Assert.eq(60, e.categories[Category.POWER])
    }

    @Test
    fun d2TpmsYellowTwoLowTiresIsEightyA() {
        val e = evalOf("D2")
        Assert.near(80.3, e.overall!!)
        Assert.eq(80, e.displayedOverall)
        Assert.eq(Grade.A, e.grade)
        Assert.eq(60, e.categories[Category.TIRE])
    }

    @Test
    fun d3AbsAndTireIsSeventyFiveB() {
        val e = evalOf("D3")
        Assert.near(75.4, e.overall!!)
        Assert.eq(75, e.displayedOverall)
        Assert.eq(Grade.B, e.grade)
        Assert.eq(50, e.categories[Category.BRAKE_STEERING])
        Assert.eq(75, e.categories[Category.TIRE])
        Assert.isTrue(e.hasRed)
    }

    @Test
    fun d3AfterAbsFixIsEightyThreeA() {
        val records = Demo.resolvedD3()
        val care = Care.evaluate(records, Profile.DEMO, Demo.ANCHOR)
        val snapshot = VehicleSnapshot.normal()
            .copy(lowTireWheels = setOf(WheelArea.FL), telltales = listOf(Fx.yellow(Telltale.TPMS)))
        val e = Scoring.evaluate(snapshot, care.score)
        Assert.eq(58, care.score, "관리 +5 (같은 날 재점검, 해소 1항목)")
        Assert.near(83.9, e.overall!!)
        Assert.eq(83, e.displayedOverall)
        Assert.eq(Grade.A, e.grade)
        Assert.isFalse(e.redCap)
    }

    @Test
    fun d3BattleIsAnUnderdogComeback() {
        val mine = fixedD3Evaluation()
        val rival = Seeds.byNickname("라이벌드라이버")!!
        val result = Battle.judge(mine, rival.evaluation())
        Assert.eq(2, result.myWins)
        Assert.eq(1, result.theirWins)
        Assert.eq(BattleSide.MINE, result.winner)
    }

    private fun fixedD3Evaluation(): Evaluation {
        val snapshot = VehicleSnapshot.normal()
            .copy(lowTireWheels = setOf(WheelArea.FL), telltales = listOf(Fx.yellow(Telltale.TPMS)))
        return Scoring.evaluate(snapshot, 58)
    }

    @Test
    fun d3BattleScoresProceedZeroZeroToTwoOne() {
        val mine = fixedD3Evaluation()
        val rival = Seeds.byNickname("라이벌드라이버")!!.evaluation()
        val result = Battle.judge(mine, rival)
        val progression = mutableListOf<String>()
        var m = 0
        var t = 0
        for (round in result.rounds) {
            if (round.winner == BattleSide.MINE) m++
            if (round.winner == BattleSide.THEIRS) t++
            progression += "$m:$t"
        }
        Assert.eq(listOf("0:0", "0:0", "0:1", "1:1", "2:1"), progression)
    }

    @Test
    fun d4BatteryNinePercentIsEightyFourA() {
        val e = evalOf("D4")
        Assert.near(84.5, e.overall!!)
        Assert.eq(84, e.displayedOverall)
        Assert.eq(Grade.A, e.grade)
        Assert.eq(90, e.categories[Category.ELECTRIC])
    }

    @Test
    fun d5OilAndTireIsSeventySixB() {
        val e = evalOf("D5")
        Assert.near(76.8, e.overall!!)
        Assert.eq(76, e.displayedOverall)
        Assert.eq(Grade.B, e.grade)
    }

    @Test
    fun d5BattleAgainstWarningCollectorIsComeback() {
        val mine = evalOf("D5")
        val opponent = Seeds.byNickname("경고등수집가")!!.evaluation()
        val result = Battle.judge(mine, opponent)
        var m = 0
        var t = 0
        val progression = mutableListOf<String>()
        for (round in result.rounds) {
            if (round.winner == BattleSide.MINE) m++
            if (round.winner == BattleSide.THEIRS) t++
            progression += "$m:$t"
        }
        Assert.eq(listOf("0:1", "1:1", "1:1", "1:1", "2:1"), progression)
        Assert.eq(BattleSide.MINE, result.winner)
    }

    @Test
    fun d6UnsupportedTireIsEightyFiveA() {
        val e = evalOf("D6")
        Assert.isNull(e.categories[Category.TIRE])
        Assert.near(85.9, e.overall!!)
        Assert.eq(85, e.displayedOverall)
        Assert.eq(Grade.A, e.grade)
    }

    @Test
    fun d6BattleDrawsTheTireRound() {
        val mine = evalOf("D6")
        val opponent = Seeds.byNickname("퇴근길질주")!!.evaluation()
        val result = Battle.judge(mine, opponent)
        Assert.eq(BattleSide.DRAW, result.rounds[2].winner)
        Assert.isNull(result.rounds[2].mine)
    }

    @Test
    fun everyScenarioMatchesItsTableRow() {
        for ((scenario, records) in Demo.scenarios()) {
            val care = Care.evaluate(records, Profile.DEMO, Demo.ANCHOR).score
            val e = Scoring.evaluate(scenario.snapshot, care)
            Assert.eq(Demo.CARE_TODAY, care, "${scenario.id} 관리")
            Assert.near(scenario.expectedOverall, e.overall!!, 0.05, "${scenario.id} 종합")
            Assert.eq(scenario.expectedDisplayed, e.displayedOverall, "${scenario.id} 표시")
            Assert.eq(scenario.expectedGrade, e.grade, "${scenario.id} 등급")
        }
    }

    @Test
    fun demoResultsDoNotDependOnToday() {
        val dates = listOf(
            java.time.LocalDate.of(2026, 11, 9),
            java.time.LocalDate.of(2026, 11, 30),
            java.time.LocalDate.of(2026, 12, 3),
            java.time.LocalDate.of(2027, 3, 14),
        )
        for (date in dates) {
            for ((scenario, records) in Demo.scenarios(date)) {
                val care = Care.evaluate(records, Profile.DEMO, date).score
                val e = Scoring.evaluate(scenario.snapshot, care)
                Assert.near(scenario.expectedOverall, e.overall!!, 0.05, "${scenario.id}@$date 종합")
                Assert.eq(scenario.expectedGrade, e.grade, "${scenario.id}@$date 등급")
            }
        }
    }

    @Test
    fun demoOpponentIsFixedPerScenario() {
        val expected = mapOf(
            "D0" to "달리는곰",
            "D1" to "세차는사랑",
            "D2" to "출근길요정",
            "D3" to "라이벌드라이버",
            "D4" to "주말캠퍼",
            "D5" to "경고등수집가",
            "D6" to "퇴근길질주",
        )
        for ((scenario, _) in Demo.scenarios()) {
            Assert.eq(expected[scenario.id], scenario.opponent, scenario.id)
            Assert.notNull(Seeds.byNickname(scenario.opponent), "${scenario.id} 상대 시드")
        }
    }

    @Test
    fun sevenScenariosAreD0ThroughD6() {
        Assert.eq(listOf("D0", "D1", "D2", "D3", "D4", "D5", "D6"), Demo.scenarios().map { it.first.id })
    }
}
