package moca.core

class BattleTextTest {
    @Test
    fun d3LineShowsActualScoreWithoutDemoTag() {
        val mine = Scoring.evaluate(Demo.snapshotOf("D3"), Demo.CARE_TODAY)
        val rival = Seeds.byNickname("라이벌드라이버")!!.evaluation()
        val result = Battle.judge(mine, rival)
        val line = BattleText.line(
            "패배",
            result.myWins,
            result.theirWins,
        )
        Assert.eq("패배  (2 : 2)", line)
    }

    @Test
    fun lineHasNoDemoSuffix() {
        Assert.eq("승리!  (1 : 0)", BattleText.line("승리!", 1, 0))
        Assert.eq("무승부  (0 : 0)", BattleText.line("무승부", 0, 0))
    }
}
