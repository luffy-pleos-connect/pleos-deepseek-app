package ai.moca.app.battle

import moca.core.Category
import moca.core.Deduction
import moca.core.Evaluation
import moca.core.Rules
import moca.core.VehicleSnapshot

/**
 * 한 구역에서 상대에게 진 것을 따라잡는 방법(최종정리 7장 기능 6번).
 * [fixes] 는 감점이 큰 것부터 상대 점수에 닿을 때까지만 고른다. 다 고쳐도 못 닿으면 가진 감점을 전부 담는다.
 */
data class CatchUpItem(
    val category: Category,
    val mine: Int?,
    val theirs: Int?,
    val fixes: List<Deduction>,
    /** [fixes] 를 모두 해결했을 때 내 점수. */
    val after: Int?,
    /** after >= theirs */
    val reaches: Boolean,
)

data class CatchUp(
    /** 내가 진 구역(구역 순서). */
    val lost: List<CatchUpItem>,
    /** 내가 앞선 구역(구역 순서). */
    val ahead: List<Category>,
    /** 내 점수가 측정 불가라 비교할 수 없는 구역. */
    val unmeasured: List<Category>,
)

object CatchUpPlanner {

    fun of(mySnapshot: VehicleSnapshot, mine: Evaluation, theirs: Evaluation): CatchUp {
        val deductions = Rules.deductions(mySnapshot)
        val lost = mutableListOf<CatchUpItem>()
        val ahead = mutableListOf<Category>()
        val unmeasured = mutableListOf<Category>()
        for (category in Category.entries) {
            val m = mine.categories[category]
            val t = theirs.categories[category]
            when {
                m == null -> unmeasured += category
                t == null -> Unit
                m > t -> ahead += category
                m < t -> lost += itemOf(category, m, t, deductions.filter { it.category == category })
            }
        }
        return CatchUp(lost, ahead, unmeasured)
    }

    private fun itemOf(category: Category, mine: Int, theirs: Int, own: List<Deduction>): CatchUpItem {
        // 구역 점수는 0 밑으로 내려가지 않으므로, 감점 합이 100 을 넘으면 깎이기 전 값(100 - 합)에서 센다.
        val total = own.sumOf { it.points }
        val base = if (mine == maxOf(0, 100 - total)) 100 - total else mine
        var score = base
        val fixes = mutableListOf<Deduction>()
        for (deduction in own.sortedByDescending { it.points }) {
            if (score >= theirs) break
            fixes += deduction
            score += deduction.points
        }
        val after = score.coerceIn(0, 100)
        return CatchUpItem(category, mine, theirs, fixes, after, after >= theirs)
    }
}

/** 목적격 조사. 받침이 있으면 «을», 없으면 «를». % 는 «퍼센트» 로 읽으므로 «를». */
fun objectParticle(text: String): String {
    val last = text.trimEnd().lastOrNull() ?: return "을"
    if (last == '%') return "를"
    if (last !in '가'..'힣') return "을"
    return if ((last - '가') % 28 == 0) "를" else "을"
}
