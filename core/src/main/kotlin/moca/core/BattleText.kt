package moca.core

/** 대전 결과 한 줄. 화면은 이 함수의 반환만 넣는다. */
object BattleText {
    fun line(winnerLabel: String, myWins: Int, theirWins: Int, demoFixed: Boolean): String {
        val suffix = if (demoFixed) "  ·  데모 시나리오" else ""
        return "$winnerLabel  ($myWins : $theirWins)$suffix"
    }
}
