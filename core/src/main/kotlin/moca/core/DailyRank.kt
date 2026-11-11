package moca.core

/** 순위표의 한 줄. `overall` 은 화면에 보이는 종합 점수(`Evaluation.displayedOverall`)다. */
data class RankRow(
    val nickname: String,
    val overall: Int,
    val grade: Grade,
    val isMe: Boolean,
)

/** `rows` 는 1위부터 꼴찌까지 전부다. `rank` 는 내 줄의 자리(1부터). */
data class RankBoard(
    val rank: Int,
    val total: Int,
    val rows: List<RankRow>,
)

/**
 * 계획 v9 의 D5(오늘의 순위). 상대는 대전의 연습 상대(Seeds.battlePool)와 같다.
 * 순위는 나보다 종합 점수가 높은 상대의 수 + 1 이고, 점수가 같으면 내가 위다.
 */
object DailyRank {

    fun board(myNickname: String, mine: Evaluation, opponents: List<Seed> = Seeds.battlePool): RankBoard? {
        val myScore = mine.overall ?: return null
        val myGrade = mine.grade ?: return null
        val myShown = mine.displayedOverall ?: return null

        val entries = mutableListOf<Entry>()
        entries += Entry(RankRow(myNickname, myShown, myGrade, isMe = true), myScore, isMe = true)

        for (seed in opponents) {
            val evaluation = seed.evaluation()
            val score = evaluation.overall ?: continue
            val grade = evaluation.grade ?: continue
            val shown = evaluation.displayedOverall ?: score.toInt()
            entries += Entry(RankRow(seed.nickname, shown, grade, isMe = false), score, isMe = false)
        }

        val ordered = entries.sortedWith(
            compareByDescending<Entry> { it.score }.thenByDescending { it.isMe },
        )
        return RankBoard(
            rank = ordered.indexOfFirst { it.isMe } + 1,
            total = ordered.size,
            rows = ordered.map { it.row },
        )
    }

    /** 내 줄과 그 위아래 `around` 줄씩. 끝에서는 있는 만큼만 돌려준다. */
    fun window(board: RankBoard, around: Int = 2): List<RankRow> {
        val index = board.rank - 1
        val from = maxOf(0, index - around)
        val to = minOf(board.rows.size, index + around + 1)
        return board.rows.subList(from, to)
    }

    private class Entry(val row: RankRow, val score: Double, val isMe: Boolean)
}
