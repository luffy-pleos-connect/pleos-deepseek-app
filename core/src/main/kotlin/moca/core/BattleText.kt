package moca.core

/** 대전 결과 한 줄. 화면은 이 함수의 반환만 넣는다. */
object BattleText {
    fun line(winnerLabel: String, myWins: Int, theirWins: Int): String =
        "$winnerLabel  ($myWins : $theirWins)"

    fun reason(myWins: Int, theirWins: Int, myTotal: Int, theirTotal: Int): String =
        if (myWins == theirWins) {
            "라운드 $myWins : $theirWins 동점 · 종합 $myTotal 대 $theirTotal"
        } else {
            "라운드 $myWins : $theirWins"
        }
}
