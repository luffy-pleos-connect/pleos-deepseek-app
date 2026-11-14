package ai.moca.app.vehicle

import moca.core.Rules

/**
 * 화면 배지·도구가 쓰는 경고 요약.
 *
 * 배지 개수는 **감점 대상 경고등만** 센다(최종정리 2.3절 「감점하지 않는 것」).
 * 연료 부족·EV 배터리 부족·안전벨트·동승석 에어백 꺼짐은 켜져 있어도 점수를 깎지 않으므로
 * "주의 n"에도 넣지 않는다. [WarningSummary.active] 에는 켜진 경고등 원본을 그대로 남긴다.
 */
object WarningCounter {

    fun summarize(active: List<ActiveWarning>, isDemo: Boolean): WarningSummary {
        var red = 0
        var yellow = 0
        for (warning in active) {
            val telltale = TelltaleTypeMap.toEngine(warning.type) ?: continue
            if (Rules.categoryOf(telltale) == null) continue
            when (TelltaleColorMap.severity(warning.color)) {
                Severity.RED -> red++
                Severity.YELLOW -> yellow++
                Severity.NONE -> Unit
            }
        }
        return WarningSummary(red, yellow, red + yellow, isDemo, active)
    }
}
