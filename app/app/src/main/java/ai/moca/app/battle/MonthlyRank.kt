package ai.moca.app.battle

import moca.core.Evaluation
import moca.core.Seed
import moca.core.Seeds

data class MonthlyRow(
    val nickname: String,
    /** 화면에 보이는 종합 점수(소수점 버림). */
    val overall: Int,
    val care: Int,
    val gradeLabel: String,
    val me: Boolean,
)

data class MonthlyRanking(
    val rows: List<MonthlyRow>,
    val myRank: Int,
    val total: Int,
    val leader: MonthlyRow,
    val gapToLeader: Int,
)

/**
 * 월간랭킹(최종정리 7장·12장). 상대는 연습 상대(Seeds.battlePool)뿐이다.
 * 순위: 보이는 종합 점수가 높은 순 → 같으면 관리 점수가 높은 쪽 → 그래도 같으면 내가 위.
 * 관리 점수가 매달 50 에서 새로 시작하므로 순위도 매달 1일에 새로 시작한다. 따로 지울 것은 없다.
 */
object MonthlyRank {

    fun of(myNickname: String, mine: Evaluation, seeds: List<Seed> = Seeds.battlePool): MonthlyRanking? {
        val shown = mine.displayedOverall ?: return null
        val grade = mine.grade ?: return null
        val me = MonthlyRow(myNickname, shown, mine.care ?: 0, grade.label, me = true)
        val others = seeds.mapNotNull { seed ->
            val evaluation = seed.evaluation()
            val overall = evaluation.displayedOverall ?: return@mapNotNull null
            val label = evaluation.grade?.label ?: return@mapNotNull null
            MonthlyRow(seed.nickname, overall, seed.care, label, me = false)
        }
        val rows = (listOf(me) + others).sortedWith(
            compareByDescending<MonthlyRow> { it.overall }
                .thenByDescending { it.care }
                .thenByDescending { it.me },
        )
        val leader = rows.first()
        return MonthlyRanking(
            rows = rows,
            myRank = rows.indexOfFirst { it.me } + 1,
            total = rows.size,
            leader = leader,
            gapToLeader = leader.overall - shown,
        )
    }
}
