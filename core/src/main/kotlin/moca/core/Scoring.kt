package moca.core

object Scoring {

    const val HEALTH_WEIGHT = 0.7
    const val CARE_WEIGHT = 0.3
    const val HEALTH_NOTICE = "S 이상은 차 상태 90점 이상일 때 받을 수 있어요"

    /** 등급을 내려면 최소 이만큼의 카테고리가 측정돼야 한다(5개 중 3개). */
    const val MIN_MEASURED_CATEGORIES = 3
    private const val EPS = 1e-9

    fun categories(snapshot: VehicleSnapshot): Map<Category, Int?> {
        val perCategory = Rules.deductions(snapshot).groupBy { it.category }
        return Category.entries.associateWith { category ->
            if (!Rules.measured(snapshot, category)) {
                null
            } else {
                val penalty = perCategory[category].orEmpty().sumOf { it.points }
                maxOf(0, 100 - penalty)
            }
        }
    }

    fun health(categories: Map<Category, Int?>): Double? {
        val measured = categories.values.filterNotNull()
        if (measured.isEmpty()) return null
        return measured.average()
    }

    fun overall(health: Double?, care: Int?): Double? {
        if (health == null || care == null) return null
        return health * HEALTH_WEIGHT + care * CARE_WEIGHT
    }

    fun gradeFor(overall: Double?, health: Double?, redCap: Boolean): GradeDecision? {
        if (overall == null || health == null) return null

        var grade = when {
            overall >= 95.0 - EPS -> Grade.S_PLUS
            overall >= 90.0 - EPS -> Grade.S
            overall >= 80.0 - EPS -> Grade.A
            overall >= 70.0 - EPS -> Grade.B
            overall >= 60.0 - EPS -> Grade.C
            else -> Grade.D
        }

        val ceiling = when {
            health >= 95.0 - EPS -> Grade.S_PLUS
            health >= 90.0 - EPS -> Grade.S
            else -> Grade.A
        }
        var healthCapped = false
        if (grade.ordinal > ceiling.ordinal) {
            grade = ceiling
            healthCapped = true
        }

        var redCapped = false
        if (redCap && grade.ordinal > Grade.B.ordinal) {
            grade = Grade.B
            redCapped = true
        }
        return GradeDecision(grade, redCapped, healthCapped)
    }

    fun evaluate(categories: Map<Category, Int?>, care: Int?, redCap: Boolean = false): Evaluation {
        val measured = categories.values.filterNotNull()
        val count = measured.size
        val sum = measured.sum()
        val health = if (count == 0) null else sum.toDouble() / count
        val hasRed = redCap

        // 카테고리 5개 중 3개 미만만 측정되면 "차 상태"를 대표하지 못한다.
        // 그런데도 등급을 주면 "등급 = 차 상태 보증"이라는 약속(계획 7장)이 깨진다.
        // 측정 불가 항목이 절반을 넘으면 등급을 내지 않고 점검을 권한다.
        if (count < MIN_MEASURED_CATEGORIES || care == null) {
            return Evaluation(
                categories = categories,
                health = health,
                care = care,
                overall = null,
                grade = null,
                redCap = false,
                displayedOverall = null,
                nextGrade = null,
                pointsToNext = null,
                healthCapped = false,
                redCapNotice = null,
                healthNotice = null,
            )
        }

        val denominator = 10 * count
        val numerator = sum * 7 + care * 3 * count
        val overall = numerator.toDouble() / denominator
        val decision = decide(numerator, denominator, sum, count, redCap = hasRed)!!
        val grade = decision.grade

        val next = grade.next()
        val points = next?.let { ceilDiv(it.threshold * denominator - numerator, denominator) }

        val uncapped = decide(numerator, denominator, sum, count, redCap = false)!!
        val redNotice = if (decision.redCapped) {
            val nextAfterFix = uncapped.grade.next()
            val target = nextAfterFix ?: uncapped.grade
            val gap = if (nextAfterFix != null) {
                ceilDiv(nextAfterFix.threshold * denominator - numerator, denominator)
            } else {
                0
            }
            "빨간 경고등을 해결하면 ${target.label}까지 ${gap}점"
        } else {
            null
        }

        val healthNotice = if (sum < 90 * count && numerator >= 90 * denominator) HEALTH_NOTICE else null

        return Evaluation(
            categories = categories,
            health = health,
            care = care,
            overall = overall,
            grade = grade,
            redCap = decision.redCapped,
            displayedOverall = numerator / denominator,
            nextGrade = next,
            pointsToNext = points,
            healthCapped = decision.healthCapped,
            redCapNotice = redNotice,
            healthNotice = healthNotice,
            hasRed = hasRed,
        )
    }

    private fun decide(
        numerator: Int,
        denominator: Int,
        healthSum: Int,
        healthCount: Int,
        redCap: Boolean,
    ): GradeDecision? {
        val thresholds = listOf(
            Grade.S_PLUS to 95,
            Grade.S to 90,
            Grade.A to 80,
            Grade.B to 70,
            Grade.C to 60,
        )
        var grade = thresholds.firstOrNull { numerator >= it.second * denominator }?.first ?: Grade.D

        val ceiling = when {
            healthSum >= 95 * healthCount -> Grade.S_PLUS
            healthSum >= 90 * healthCount -> Grade.S
            else -> Grade.A
        }
        var healthCapped = false
        if (grade.ordinal > ceiling.ordinal) {
            grade = ceiling
            healthCapped = true
        }

        var redCapped = false
        if (redCap && grade.ordinal > Grade.B.ordinal) {
            grade = Grade.B
            redCapped = true
        }
        return GradeDecision(grade, redCapped, healthCapped)
    }

    private fun ceilDiv(numerator: Int, denominator: Int): Int =
        if (numerator <= 0) 0 else (numerator + denominator - 1) / denominator

    fun evaluate(snapshot: VehicleSnapshot, care: Int?, redCap: Boolean = Rules.hasRed(snapshot)): Evaluation =
        evaluate(categories(snapshot), care, redCap)
}
