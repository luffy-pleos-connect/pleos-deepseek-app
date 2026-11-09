package moca.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

object Care {
    const val START = 50
    const val INSPECTION_BONUS = 2
    const val STREAK_EVERY = 7
    const val STREAK_BONUS = 5
    const val RESOLVED_BONUS = 5
    const val RESOLVED_DAILY_CAP = 10
    const val DECAY_AFTER_DAYS = 14L
    const val DECAY_STEP = 1

    fun evaluate(inspections: List<Inspection>, profile: Profile, today: LocalDate): CareResult {
        val ordered = inspections
            .filter { it.profile == profile && !it.inspectedAt.isAfter(today) }
            .sortedWith(compareBy({ it.inspectedAt }, { it.seq }))

        if (profile == Profile.DEMO) {
            val start = ordered.firstOrNull()?.inspectedAt ?: today
            return CareResult(simulate(ordered, start, today), null, null)
        }

        val month = YearMonth.from(today)
        val start = month.atDay(1)
        val score = simulate(ordered, start, today)
        return CareResult(score, "${month.monthValue}월 시즌", previousSeasonGrade(ordered, month.minusMonths(1)))
    }

    fun previousSeasonGrade(ordered: List<Inspection>, month: YearMonth): Grade? {
        val indexes = ordered.indices.filter { YearMonth.from(ordered[it].inspectedAt) == month }
        val lastIndex = indexes.maxOrNull() ?: return null
        val care = simulate(ordered, month.atDay(1), ordered[lastIndex].inspectedAt)
        return Scoring.evaluate(ordered[lastIndex].snapshot, care).grade
    }

    private fun simulate(ordered: List<Inspection>, start: LocalDate, end: LocalDate): Int {
        var score = START
        var streak = 0
        var lastInspection: LocalDate? = null

        val byDay = ordered.withIndex()
            .filter { !it.value.inspectedAt.isBefore(start) && !it.value.inspectedAt.isAfter(end) }
            .groupBy { it.value.inspectedAt }
            .mapValues { (_, entries) -> entries.sortedBy { it.value.seq } }

        var day = start
        while (!day.isAfter(end)) {
            val entries = byDay[day]
            if (entries.isNullOrEmpty()) {
                val baseline = lastInspection ?: start
                if (ChronoUnit.DAYS.between(baseline, day) > DECAY_AFTER_DAYS) {
                    score -= DECAY_STEP
                }
            } else {
                score += INSPECTION_BONUS
                streak = if (lastInspection != null && ChronoUnit.DAYS.between(lastInspection, day) == 1L) {
                    streak + 1
                } else {
                    1
                }
                if (streak % STREAK_EVERY == 0) score += STREAK_BONUS

                var bonus = 0
                for ((index, _) in entries) {
                    if (index == 0) continue
                    val before = Rules.groupKeys(ordered[index - 1].snapshot)
                    val now = Rules.groupKeys(ordered[index].snapshot)
                    bonus = minOf(RESOLVED_DAILY_CAP, bonus + (before - now).size * RESOLVED_BONUS)
                }
                score += bonus
                lastInspection = day
            }
            score = score.coerceIn(0, 100)
            day = day.plusDays(1)
        }
        return score
    }
}
