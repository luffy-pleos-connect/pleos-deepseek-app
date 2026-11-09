package ai.moca.app.ui

import ai.moca.app.MocaApp
import ai.moca.app.R
import ai.moca.app.score.ScoreBridge
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import moca.core.Category
import moca.core.Evaluation

/**
 * 상태관리: 카테고리 카드 5개 + 할 일.
 * 카테고리 점수는 :core 엔진 결과를 그대로 쓴다(null = 측정 불가, 미지원).
 */
class StatusFragment : Fragment(R.layout.fragment_status) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as MocaApp
        val container = view.findViewById<LinearLayout>(R.id.categoryContainer)
        val todo = view.findViewById<LinearLayout>(R.id.todoContainer)
        val worst = view.findViewById<TextView>(R.id.worstCategory)

        viewLifecycleOwner.lifecycleScope.launch {
            val source = app.vehicle()
            val snapshot = source.read()
            val eval = runCatching {
                ScoreBridge.evaluate(snapshot.items, source.cachedWarnings(), snapshot.isDemo)
            }.onFailure { android.util.Log.e("MOCA_SCORE", "evaluate failed", it) }.getOrNull()

            container.removeAllViews()
            todo.removeAllViews()

            if (eval == null) {
                android.util.Log.w(
                    "MOCA_SCORE",
                    "eval null: items=${snapshot.items.keys} warnings=${source.cachedWarnings()}",
                )
                worst.text = getString(R.string.c12_inspect_first)
                return@launch
            }

            for (category in Category.entries) {
                container.addView(card(category, eval.categories[category], eval))
            }

            val worstCategory = eval.categories.entries
                .filter { it.value != null }
                .minByOrNull { it.value!! }
            worst.text = when {
                worstCategory == null -> getString(R.string.c06_unmeasurable)
                worstCategory.value!! >= 100 -> "지금은 모두 정상 범위예요"
                else -> "${worstCategory.key.label}부터 보면 좋아요 (${worstCategory.value}점)"
            }

            val lower = eval.categories.entries.filter { (it.value ?: 100) < 100 }
            if (lower.isEmpty()) {
                todo.addView(todoRow("지금 해결할 항목이 없어요", positive = true))
            } else {
                for ((category, score) in lower.sortedBy { it.value }) {
                    todo.addView(todoRow(getString(R.string.c07_bonus_fixed, category.label)))
                }
            }

            if (snapshot.isDemo) {
                worst.text = getString(R.string.c13_badge_demo) + " · " + worst.text
            }
        }
    }

    private fun card(category: Category, score: Int?, eval: Evaluation): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
            setBackgroundResource(ai.pleos.ds.R.drawable.pleos_card_bg)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = 24 }
        }
        val title = TextView(ctx).apply {
            text = category.label
            setTextAppearance(ai.pleos.ds.R.style.Pleos_Text_Title_S)
            setTextColor(ContextCompat.getColor(ctx, ai.pleos.ds.R.color.pleos_text_dark_primary))
        }
        val state = TextView(ctx).apply {
            text = when {
                score == null -> getString(R.string.c06_unmeasurable)
                score >= 100 -> "정상 · $score"
                score >= 70 -> "주의 · $score"
                else -> "위험 · $score"
            }
            setTextAppearance(ai.pleos.ds.R.style.Pleos_Text_Body_M)
            setTextColor(
                ContextCompat.getColor(
                    ctx,
                    when {
                        score == null -> ai.pleos.ds.R.color.pleos_text_dark_tertiary
                        score >= 100 -> ai.pleos.ds.R.color.pleos_informative_positive
                        score >= 70 -> ai.pleos.ds.R.color.pleos_regulation_yellow
                        else -> ai.pleos.ds.R.color.pleos_regulation_red
                    },
                ),
            )
        }
        box.addView(title)
        box.addView(state)
        return box
    }

    private fun todoRow(text: String, positive: Boolean = false): View {
        val ctx = requireContext()
        return TextView(ctx).apply {
            this.text = text
            setTextAppearance(ai.pleos.ds.R.style.Pleos_Text_Body_M)
            setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (positive) {
                        ai.pleos.ds.R.color.pleos_informative_positive
                    } else {
                        ai.pleos.ds.R.color.pleos_text_dark_secondary
                    },
                ),
            )
            setPadding(0, 16, 0, 16)
        }
    }
}
