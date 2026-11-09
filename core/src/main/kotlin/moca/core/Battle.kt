package moca.core

object Battle {
    val ROUND_ORDER = listOf(
        Category.POWER,
        Category.BRAKE_STEERING,
        Category.TIRE,
        Category.ELECTRIC,
        Category.SAFETY,
    )

    fun judge(mine: Evaluation, theirs: Evaluation): BattleResult {
        val rounds = ROUND_ORDER.map { category ->
            val a = mine.categories[category]
            val b = theirs.categories[category]
            val winner = when {
                a == null || b == null -> BattleSide.DRAW
                a > b -> BattleSide.MINE
                b > a -> BattleSide.THEIRS
                else -> BattleSide.DRAW
            }
            BattleRound(category, a, b, winner)
        }

        val myWins = rounds.count { it.winner == BattleSide.MINE }
        val theirWins = rounds.count { it.winner == BattleSide.THEIRS }

        if (myWins != theirWins) {
            return BattleResult(
                rounds, myWins, theirWins,
                if (myWins > theirWins) BattleSide.MINE else BattleSide.THEIRS,
                tieBreakUsed = false,
                myOverall = mine.overall,
                theirOverall = theirs.overall,
            )
        }

        val a = mine.overall
        val b = theirs.overall
        val winner = when {
            a == null || b == null -> BattleSide.DRAW
            a > b -> BattleSide.MINE
            b > a -> BattleSide.THEIRS
            else -> BattleSide.DRAW
        }
        return BattleResult(
            rounds, myWins, theirWins, winner,
            tieBreakUsed = winner != BattleSide.DRAW,
            myOverall = a,
            theirOverall = b,
        )
    }

    fun judge(
        mineSnapshot: VehicleSnapshot,
        myCare: Int?,
        theirsSnapshot: VehicleSnapshot,
        theirCare: Int?,
    ): BattleResult = judge(Scoring.evaluate(mineSnapshot, myCare), Scoring.evaluate(theirsSnapshot, theirCare))
}
