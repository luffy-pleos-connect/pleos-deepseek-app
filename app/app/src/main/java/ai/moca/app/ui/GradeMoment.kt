package ai.moca.app.ui

import moca.core.Grade

/**
 * 홈에서 등급이 올랐는지 한 줄로 정한다.
 * 화면과 저장소는 이 결과를 그리기만 한다.
 */
object GradeMoment {

    data class Moment(val text: String)

    private const val ARROW = " → "
    private const val SEP = " · "

    fun decide(
        previousGrade: String?,
        previousRank: Int?,
        nowGrade: Grade?,
        nowRank: Int?,
        driving: Boolean,
    ): Moment? {
        if (driving || previousGrade == null || nowGrade == null) return null
        val previous = Grade.entries.firstOrNull { it.label == previousGrade } ?: return null
        if (nowGrade.ordinal <= previous.ordinal) return null
        val gradeText = previous.label + ARROW + nowGrade.label
        val text = if (previousRank != null && nowRank != null && nowRank < previousRank) {
            gradeText + SEP + previousRank + "위" + ARROW + nowRank + "위"
        } else {
            gradeText
        }
        return Moment(text)
    }
}
