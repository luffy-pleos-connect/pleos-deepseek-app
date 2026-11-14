package ai.moca.app.battle

import moca.core.Inspection
import moca.core.Rules
import java.time.LocalDate
import java.time.YearMonth

/** 월간랭킹 화면 «이번 달 내 관리 요약»의 숫자. 기록에서 세고 지어내지 않는다. */
data class CareSummary(
    /** 이번 달에 점검한 날 수(같은 날 여러 번은 하루). */
    val daysInspected: Int,
    /** 오늘부터 거꾸로 하루도 빠지지 않고 점검한 날 수. 오늘 점검이 없으면 0. */
    val streak: Int,
    /** 이번 달에 해결한 감점 묶음 수. */
    val resolved: Int,
    /** 1위와의 점수 차이. 순위가 없으면 null. */
    val gapToLeader: Int?,
    /** 따라잡는 방법: 지금 내 감점 중 큰 것부터 최대 2개의 이름. */
    val tips: List<String>,
)

object CareSummaryBuilder {

    /** [records] 는 시간순이다. 지금 감점은 마지막 기록의 스냅샷에서 본다. */
    fun of(records: List<Inspection>, today: LocalDate, ranking: MonthlyRanking?): CareSummary {
        val month = YearMonth.from(today)
        val days = records.map { it.inspectedAt }.filter { !it.isAfter(today) }.toSet()

        var streak = 0
        var day = today
        while (day in days) {
            streak++
            day = day.minusDays(1)
        }

        // 앞 기록에 있던 묶음이 뒤 기록에서 사라졌고, 뒤 기록이 그 묶음을 읽을 수 있었을 때만 해결이다.
        val resolved = records.zipWithNext().sumOf { (before, after) ->
            if (YearMonth.from(after.inspectedAt) != month || after.inspectedAt.isAfter(today)) {
                0
            } else {
                val readable = Rules.readableGroups(after.snapshot)
                val still = Rules.groupKeys(after.snapshot)
                Rules.groupKeys(before.snapshot).count { it in readable && it !in still }
            }
        }

        val tips = records.lastOrNull()?.let { last ->
            Rules.deductions(last.snapshot).sortedByDescending { it.points }.take(2).map { it.label }
        }.orEmpty()

        return CareSummary(
            daysInspected = days.count { YearMonth.from(it) == month },
            streak = streak,
            resolved = resolved,
            gapToLeader = ranking?.gapToLeader,
            tips = tips,
        )
    }

    /** 서버가 쓴 문장에 확인된 값에 없는 숫자가 있으면 쓰지 않는다. */
    fun usesOnly(text: String, known: Collection<Int>): Boolean =
        Regex("\\d+").findAll(text).all { it.value.toIntOrNull() in known }
}
