package moca.core

class BattleTest {

    private fun battle(mine: Evaluation, theirs: Evaluation) = Battle.judge(mine, theirs)

    private fun seed(nickname: String): Seed = Seeds.byNickname(nickname)!!

    @Test
    fun roundOrderIsPowerBrakeTireElectricSafety() {
        val result = battle(Fx.evaluation(), Fx.evaluation())
        Assert.eq(
            listOf(Category.POWER, Category.BRAKE_STEERING, Category.TIRE, Category.ELECTRIC, Category.SAFETY),
            result.rounds.map { it.category },
        )
    }

    @Test
    fun fiveRoundsAlways() {
        Assert.eq(5, battle(Fx.evaluation(), Fx.evaluation()).rounds.size)
    }

    @Test
    fun higherCategoryWinsItsRound() {
        val result = battle(Fx.evaluation(power = 90), Fx.evaluation(power = 80))
        Assert.eq(BattleSide.MINE, result.rounds.first().winner)
    }

    @Test
    fun equalCategoryIsDraw() {
        val result = battle(Fx.evaluation(tire = 70), Fx.evaluation(tire = 70))
        Assert.eq(BattleSide.DRAW, result.rounds[2].winner)
    }

    @Test
    fun e5UnsupportedTireMakesThatRoundDraw() {
        val mine = Scoring.evaluate(VehicleSnapshot.normal().copy(unsupported = setOf(Item.TIRE)), 52)
        val theirs = Fx.evaluation(tire = 60)
        val result = battle(mine, theirs)
        Assert.isNull(result.rounds[2].mine)
        Assert.eq(BattleSide.DRAW, result.rounds[2].winner)
    }

    @Test
    fun e5OtherRoundsStillCount() {
        val mine = Scoring.evaluate(VehicleSnapshot.normal().copy(unsupported = setOf(Item.TIRE)), 52)
        val theirs = Fx.evaluation(tire = 60, power = 60)
        val result = battle(mine, theirs)
        Assert.eq(1, result.myWins, "동력만 이기고 나머지는 무승부")
        Assert.eq(0, result.theirWins)
        Assert.eq(BattleSide.MINE, result.winner)
    }

    @Test
    fun bothNullRoundIsDraw() {
        val mine = Scoring.evaluate(VehicleSnapshot.normal().copy(unsupported = setOf(Item.TIRE)), 52)
        val theirs = Scoring.evaluate(VehicleSnapshot.normal().copy(unsupported = setOf(Item.TIRE)), 52)
        Assert.eq(BattleSide.DRAW, battle(mine, theirs).rounds[2].winner)
    }

    @Test
    fun roundWinsDecideWithoutTieBreak() {
        val mine = Fx.evaluation(power = 100, brake = 100, tire = 50, electric = 50, safety = 50)
        val theirs = Fx.evaluation(power = 90, brake = 90, tire = 100, electric = 100, safety = 100)
        val result = battle(mine, theirs)
        Assert.eq(2, result.myWins)
        Assert.eq(3, result.theirWins)
        Assert.eq(BattleSide.THEIRS, result.winner)
        Assert.isFalse(result.tieBreakUsed)
    }

    @Test
    fun equalRoundWinsFallBackToOverall() {
        val mine = Fx.evaluation(power = 90, brake = 90, tire = 40, electric = 40, care = 100)
        val theirs = Fx.evaluation(power = 80, brake = 80, tire = 100, electric = 100, care = 10)
        val result = battle(mine, theirs)
        Assert.eq(2, result.myWins)
        Assert.eq(2, result.theirWins)
        Assert.eq(BattleSide.MINE, result.winner)
        Assert.isTrue(result.tieBreakUsed)
    }

    @Test
    fun equalRoundWinsAndOverallIsDraw() {
        val a = Fx.evaluation()
        val b = Fx.evaluation()
        val result = battle(a, b)
        Assert.eq(0, result.myWins)
        Assert.eq(0, result.theirWins)
        Assert.eq(BattleSide.DRAW, result.winner)
        Assert.isFalse(result.tieBreakUsed, "종합까지 같으면 무승부")
    }

    @Test
    fun swappingSidesFlipsTheWinner() {
        val a = Fx.evaluation(power = 90, brake = 90, tire = 40, electric = 40, care = 100)
        val b = Fx.evaluation(power = 80, brake = 80, tire = 100, electric = 100, care = 10)
        Assert.eq(BattleSide.MINE, battle(a, b).winner)
        Assert.eq(BattleSide.THEIRS, battle(b, a).winner)
    }

    @Test
    fun swappingSidesFlipsEachRound() {
        val a = Fx.evaluation(power = 90)
        val b = Fx.evaluation(power = 80)
        Assert.eq(BattleSide.MINE, battle(a, b).rounds[0].winner)
        Assert.eq(BattleSide.THEIRS, battle(b, a).rounds[0].winner)
    }

    @Test
    fun d3PreFixLosesOnJuniorRoundsAndTieBreak() {
        val mine = Demo.evaluateOf("D3")
        val rival = seed("라이벌드라이버").evaluation()
        val first = battle(mine, rival)
        Assert.eq(BattleSide.DRAW, first.rounds[0].winner, "동력 100 대 100")
        Assert.eq(BattleSide.THEIRS, first.rounds[1].winner)
        Assert.eq(BattleSide.THEIRS, first.rounds[2].winner)
        Assert.eq(BattleSide.MINE, first.rounds[3].winner)
        Assert.eq(BattleSide.MINE, first.rounds[4].winner)
        Assert.eq(2, first.myWins)
        Assert.eq(2, first.theirWins)
        Assert.eq(BattleSide.THEIRS, first.winner, "종합 75.4 대 88.2")
    }

    @Test
    fun e5BattleAgainstSeedKeepsTireRoundScoreless() {
        val mine = Demo.evaluateOf("D6")
        val opponent = seed("퇴근길질주").evaluation()
        val result = battle(mine, opponent)
        Assert.isNull(result.rounds[2].mine)
        Assert.eq(75, result.rounds[2].theirs)
    }

    @Test
    fun bothSidesNullInEveryRoundIsScorelessDraw() {
        val mine = Scoring.evaluate(VehicleSnapshot.normal().copy(unsupported = Item.values().toSet()), 52)
        val theirs = Scoring.evaluate(VehicleSnapshot.normal().copy(unsupported = Item.values().toSet()), 52)
        val result = battle(mine, theirs)
        Assert.eq(5, result.rounds.count { it.winner == BattleSide.DRAW })
        Assert.eq(BattleSide.DRAW, result.winner)
    }

    @Test
    fun judgeIsDeterministic() {
        val a = Fx.evaluation(power = 90, brake = 60, tire = 80, electric = 70, safety = 100, care = 55)
        val b = Fx.evaluation(power = 85, brake = 65, tire = 80, electric = 70, safety = 95, care = 55)
        Assert.eq(battle(a, b), battle(a, b))
    }

    @Test
    fun fiveRoundsUseCategoryScoresNotGrade() {
        val result = battle(seed("새벽드라이버").evaluation(), seed("주차장지킴이").evaluation())
        Assert.eq(4, result.myWins, "안전 100 대 100 무승부")
        Assert.eq(BattleSide.MINE, result.winner)
    }
}
