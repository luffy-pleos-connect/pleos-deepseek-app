package moca.core

/**
 * 홈과 순위 카드의 순위 한 줄(계획 v10 3.2 의 18번).
 * 서버 `/rank` 가 실제 사용자를 셌는지에 따라 글자가 달라진다. `real` 이 0 이면 연습 순위다.
 */
object RankText {
    fun line(rank: Int, of: Int, real: Int): String =
        if (real <= 0) {
            "오늘 ${rank}위 · ${of}명 중 · 연습 순위"
        } else {
            "오늘 ${rank}위 · ${of}명 중 · 실제 사용자 ${real}명 포함"
        }
}
