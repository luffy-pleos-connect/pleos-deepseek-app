package ai.moca.app.ui

import ai.moca.app.MocaApp
import ai.moca.app.R
import ai.moca.app.score.ScoreBridge
import ai.moca.app.vehicle.WarningSummary
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * 홈: 등급 카드 + 기능 타일 4개.
 * P1 = 내 등급 + 주의 개수. 좁은 창에서는 종합·건강·관리 값을 숨긴다(layout-port).
 *
 * 이번 단계에는 등급 계산(:core) 연결 전이므로 등급 자리는 `—` 로 두고,
 * 차량에서 읽은 원본값(배터리 %)만 보조 줄에 표시한다. 점수를 지어내지 않는다.
 */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private lateinit var gradeValue: TextView
    private lateinit var gradeHint: TextView
    private lateinit var warningSummary: TextView
    private lateinit var totalValue: TextView
    private lateinit var healthValue: TextView
    private lateinit var careValue: TextView
    private lateinit var lastInspection: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        gradeValue = view.findViewById(R.id.gradeValue)
        gradeHint = view.findViewById(R.id.gradeHint)
        warningSummary = view.findViewById(R.id.warningSummary)
        totalValue = view.findViewById(R.id.totalValue)
        healthValue = view.findViewById(R.id.healthValue)
        careValue = view.findViewById(R.id.careValue)
        lastInspection = view.findViewById(R.id.lastInspection)

        val app = requireActivity().application as MocaApp
        val activity = requireActivity() as ai.moca.app.MainActivity

        view.findViewById<View>(R.id.tileInspect).setOnClickListener { activity.goInspect() }
        view.findViewById<View>(R.id.tileStatus).setOnClickListener { activity.goStatus() }
        view.findViewById<View>(R.id.tileVoice).setOnClickListener { activity.goVoice() }
        view.findViewById<View>(R.id.tileBattle).setOnClickListener { activity.goBattle() }

        val source = app.vehicle()
        renderWarnings(source.cachedWarnings(), source.cachedWarnings().isDemo)

        viewLifecycleOwner.lifecycleScope.launch {
            val snapshot = source.read()
            renderReadings(snapshot.items, source.cachedWarnings(), snapshot.isDemo)
        }

        source.startWatchingWarnings { s -> if (isAdded) renderWarnings(s, s.isDemo) }
    }

    private fun renderWarnings(s: WarningSummary, isDemo: Boolean) {
        val text = if (s.total == 0) {
            getString(R.string.home_warning_none)
        } else {
            getString(R.string.home_warning_count, s.total)
        }
        warningSummary.text = if (isDemo) "$text · ${getString(R.string.c13_badge_demo)}" else text
        val colorRes = when {
            s.redCount > 0 -> ai.pleos.ds.R.color.pleos_regulation_red
            s.yellowCount > 0 -> ai.pleos.ds.R.color.pleos_regulation_yellow
            else -> ai.pleos.ds.R.color.pleos_text_dark_primary
        }
        warningSummary.setTextColor(requireContext().getColor(colorRes))
    }

    /** 원본값을 :core 점수 엔진에 넣어 등급·종합·건강·관리를 계산해 보여 준다. */
    private fun renderReadings(
        items: Map<String, ai.moca.app.vehicle.ItemReading>,
        warnings: WarningSummary,
        isDemo: Boolean,
    ) {
        val evaluation = runCatching { ScoreBridge.evaluate(items, warnings, isDemo) }.getOrNull()
        if (evaluation?.grade == null) {
            gradeValue.text = getString(R.string.home_grade_placeholder)
            gradeHint.text = getString(R.string.c03_first_grade)
            totalValue.text = getString(R.string.home_grade_placeholder)
            healthValue.text = getString(R.string.home_grade_placeholder)
            careValue.text = getString(R.string.home_grade_placeholder)
        } else {
            gradeValue.text = evaluation.grade.label
            totalValue.text = evaluation.displayedOverall?.toString() ?: getString(R.string.home_grade_placeholder)
            healthValue.text = evaluation.health?.toInt()?.toString() ?: getString(R.string.home_grade_placeholder)
            careValue.text = evaluation.care?.toString() ?: getString(R.string.home_grade_placeholder)
            gradeHint.text = when {
                evaluation.redCapNotice != null -> evaluation.redCapNotice
                evaluation.nextGrade != null && evaluation.pointsToNext != null ->
                    getString(R.string.c04_next_grade, evaluation.nextGrade.label, evaluation.pointsToNext)
                else -> getString(R.string.c03_first_grade)
            }
        }
        lastInspection.text = getString(R.string.home_never_inspected)
        if (isDemo) {
            lastInspection.text = "${getString(R.string.c13_badge_demo)} · ${lastInspection.text}"
        }
    }
}
