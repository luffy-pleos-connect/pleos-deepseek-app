package moca.core

/**
 * 홈의 「등급을 올리려면」 한 줄(계획 v10 3.2 의 8번).
 * 다음 등급이 없으면 보여 줄 것이 없고(null), 가장 큰 문제가 있으면 그것부터 고르게 한다.
 */
object GradeHow {
    const val CARE_LINE = "점검을 매일 이어 가면 관리 점수가 올라요"

    fun line(evaluation: Evaluation, top: Deduction?): String? {
        if (evaluation.nextGrade == null) return null
        if (top == null) return CARE_LINE
        return "「${top.label}」부터 해결해 보세요"
    }
}
