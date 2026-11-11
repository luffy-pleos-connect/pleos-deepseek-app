package moca.core

class BattleTextTest {
    @Test
    fun d3FixedDemoLineIsComebackWin() {
        val mine = Scoring.evaluate(Demo.snapshotOf("D3"), Demo.CARE_TODAY)
        val rival = Seeds.byNickname("라이벌드라이버")!!.evaluation()
        val fixed = Demo.applyFixedRounds(Battle.judge(mine, rival), "D3")
        val line = BattleText.line(
            "승리!",
            fixed.myWins,
            fixed.theirWins,
            Demo.fixedBattleRounds("D3") != null,
        )
        Assert.eq("승리!  (2 : 1)  ·  데모 시나리오", line)
    }
}
