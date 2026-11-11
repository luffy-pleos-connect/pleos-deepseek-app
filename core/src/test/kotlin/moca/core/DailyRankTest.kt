package moca.core

// 계획 v9 의 D5(오늘의 순위)의 규칙이다.
// 순위는 종합 점수가 나보다 높은 상대의 수 + 1 이다. 점수가 같으면 내가 위다.
// 상대는 대전의 연습 상대(Seeds.battlePool)와 같다. 화면은 이것을 「연습 순위」라고 적는다.
class DailyRankTest {
    private fun eval(score: Int, care: Int) =
        Scoring.evaluate(Category.entries.associateWith { score as Int? }, care)

    private val pool = Seeds.battlePool

    @Test
    fun noBoardWithoutMyScore() {
        val unmeasured = Scoring.evaluate(Category.entries.associateWith { null as Int? }, 50)
        Assert.isNull(DailyRank.board("나", unmeasured))
    }

    @Test
    fun perfectCarIsFirstAndWorstCarIsLast() {
        val best = DailyRank.board("나", eval(100, 100))!!
        Assert.eq(1, best.rank)
        Assert.eq(pool.size + 1, best.total)
        val worst = DailyRank.board("나", eval(0, 0))!!
        Assert.eq(pool.size + 1, worst.rank)
    }

    @Test
    fun rankIsOnePlusNumberOfHigherOpponents() {
        val mine = eval(80, 60)
        val board = DailyRank.board("나", mine)!!
        val higher = pool.count { it.evaluation().overall!! > mine.overall!! }
        Assert.eq(higher + 1, board.rank)
    }

    @Test
    fun rowsAreSortedAndHaveExactlyOneMe() {
        val mine = eval(80, 60)
        val board = DailyRank.board("모카", mine)!!
        Assert.eq(pool.size + 1, board.rows.size)
        Assert.eq(1, board.rows.count { it.isMe })
        val me = board.rows[board.rank - 1]
        Assert.isTrue(me.isMe, "rank 번째 줄이 나")
        Assert.eq("모카", me.nickname)
        Assert.eq(mine.displayedOverall, me.overall)
        Assert.eq(mine.grade, me.grade)
        Assert.eq(board.rows.map { it.overall }.sortedDescending(), board.rows.map { it.overall })
    }

    @Test
    fun tieGoesToMe() {
        val seed = pool.maxBy { it.evaluation().overall!! }
        val board = DailyRank.board("나", seed.evaluation())!!
        Assert.eq(1, board.rank)
    }

    @Test
    fun demoOnlyOpponentsAreNotRanked() {
        val board = DailyRank.board("나", eval(80, 60))!!
        val demoOnly = Seeds.all.filter { it.demoOnly }.map { it.nickname }.toSet()
        Assert.isTrue(board.rows.none { !it.isMe && it.nickname in demoOnly })
    }

    @Test
    fun windowShowsMeWithTwoAboveAndTwoBelow() {
        val board = DailyRank.board("나", eval(80, 60))!!
        val window = DailyRank.window(board)
        val from = maxOf(0, board.rank - 1 - 2)
        val to = minOf(board.rows.size, board.rank - 1 + 3)
        Assert.eq(board.rows.subList(from, to), window)
        Assert.isTrue(window.any { it.isMe })
    }

    @Test
    fun windowAtTheTopStartsWithMe() {
        val board = DailyRank.board("나", eval(100, 100))!!
        val window = DailyRank.window(board)
        Assert.isTrue(window.first().isMe)
        Assert.eq(3, window.size)
    }
}
