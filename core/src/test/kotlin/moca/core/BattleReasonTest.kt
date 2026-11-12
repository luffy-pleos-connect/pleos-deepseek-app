package moca.core

// 계획 v10 3.2 의 6번: 대전 결과 아래에 왜 그렇게 됐는지 한 줄. lead 가 쓴 테스트다. 워커는 고치지 않는다.
class BattleReasonTest {
    @Test
    fun tieIsDecidedByOverallAndSaysSo() {
        Assert.eq("라운드 2 : 2 동점 · 종합 75 대 88", BattleText.reason(2, 2, 75, 88))
        Assert.eq("라운드 0 : 0 동점 · 종합 80 대 80", BattleText.reason(0, 0, 80, 80))
    }

    @Test
    fun roundsAloneWhenNotTied() {
        Assert.eq("라운드 1 : 0", BattleText.reason(1, 0, 85, 84))
        Assert.eq("라운드 1 : 3", BattleText.reason(1, 3, 70, 90))
    }

    @Test
    fun d3AgainstRivalReadsAsOnScreen() {
        val mine = Scoring.evaluate(Demo.snapshotOf("D3"), Demo.CARE_TODAY)
        val rival = Seeds.byNickname("라이벌드라이버")!!.evaluation()
        val result = Battle.judge(mine, rival)
        Assert.eq(
            "라운드 2 : 2 동점 · 종합 75 대 88",
            BattleText.reason(result.myWins, result.theirWins, mine.displayedOverall!!, rival.displayedOverall!!),
        )
    }
}
