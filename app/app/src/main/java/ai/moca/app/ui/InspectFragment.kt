package ai.moca.app.ui

import ai.moca.app.MocaApp
import ai.moca.app.R
import ai.moca.app.score.ScoreBridge
import ai.moca.app.vehicle.ItemReading
import ai.moca.app.vehicle.WarningSummary
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** 점검 결과: 등급 자리 + 카테고리 5개 + 버튼 2개. 이번 단계는 원본값 요약만 보여 준다. */
class InspectFragment : Fragment(R.layout.fragment_inspect) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val activity = requireActivity() as ai.moca.app.MainActivity
        val app = requireActivity().application as MocaApp

        val grade = view.findViewById<TextView>(R.id.resultGrade)
        val delta = view.findViewById<TextView>(R.id.resultDelta)
        val reason = view.findViewById<TextView>(R.id.resultReason)

        view.findViewById<View>(R.id.buttonToStatus).setOnClickListener { activity.goStatus() }
        view.findViewById<View>(R.id.buttonInspectAgain).setOnClickListener { runInspection(grade, delta, reason) }

        runInspection(grade, delta, reason)
        activity.title = getString(R.string.inspect_running)
    }

    private fun runInspection(grade: TextView, delta: TextView, reason: TextView) {
        val app = requireActivity().application as MocaApp
        grade.text = getString(R.string.inspect_running)
        delta.text = ""
        reason.text = ""
        viewLifecycleOwner.lifecycleScope.launch {
            val source = app.vehicle()
            val snapshot = source.read()
            val eval = runCatching {
                ScoreBridge.evaluate(snapshot.items, source.cachedWarnings(), snapshot.isDemo)
            }.getOrNull()
            if (eval?.grade != null) {
                grade.text = "${eval.grade.label} · ${eval.displayedOverall}"
                delta.text = eval.categories.entries.joinToString("  ") { (c, v) ->
                    "${c.label} ${v ?: "—"}"
                }
                reason.text = when {
                    eval.redCapNotice != null -> eval.redCapNotice
                    eval.nextGrade != null && eval.pointsToNext != null ->
                        getString(R.string.c04_next_grade, eval.nextGrade.label, eval.pointsToNext)
                    else -> ""
                }
            } else {
                grade.text = summarize(snapshot.items)
                delta.text = ""
                reason.text = reasons(snapshot.items)
            }
            if (snapshot.isDemo) {
                reason.text = getString(R.string.c13_badge_demo) + " · " + (reason.text ?: "")
            }
        }
    }

    private fun summarize(items: Map<String, ItemReading>): String {
        val lines = buildList {
            items["oil_engine"]?.let { add("오일 ${label(it)}") }
            items["tire_low"]?.let { add("타이어 ${label(it)}") }
            items["soc"]?.let { add("배터리 ${it.value?.toInt() ?: "-"}%") }
        }
        return if (lines.isEmpty()) getString(R.string.c06_unmeasurable) else lines.joinToString(" · ")
    }

    private fun label(r: ItemReading): String = when {
        !r.supported -> getString(R.string.c05_unsupported)
        r.value == null -> getString(R.string.c06_unmeasurable)
        r.value == 0.0 -> "정상"
        else -> "주의"
    }

    private fun reasons(items: Map<String, ItemReading>): String {
        val bad = items.filterValues { it.supported && (it.value ?: 0.0) > 0.0 }.keys
        return if (bad.isEmpty()) "" else "감점 항목: ${bad.joinToString(", ")}"
    }
}
