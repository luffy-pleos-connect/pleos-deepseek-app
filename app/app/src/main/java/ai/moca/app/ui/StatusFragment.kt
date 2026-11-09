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
        val trend = view.findViewById<LinearLayout>(R.id.trendContainer)
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

            // 추이(절단 규칙 4번: 선 그래프 대신 막대 5개로 단순화).
            // 이번 단계는 기록 저장(Room)이 없어 현재 카테고리 점수를 막대로 보여 준다.
            trend.removeAllViews()
            for (category in Category.entries) {
                trend.addView(trendBar(category, eval.categories[category]))
            }

            if (snapshot.isDemo) {
                worst.text = getString(R.string.c13_badge_demo) + " · " + worst.text
            }
        }
    }

    private fun card(category: Category, score: Int?, eval: Evaluation): View {
        val ctx = requireContext()
        val detail = TextView(ctx).apply {
            text = detailText(category, score, eval)
            setTextAppearance(ai.pleos.ds.R.style.Pleos_Text_Label_S)
            setTextColor(ContextCompat.getColor(ctx, ai.pleos.ds.R.color.pleos_text_dark_tertiary))
            visibility = View.GONE
            setPadding(0, 16, 0, 0)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
            setBackgroundResource(ai.pleos.ds.R.drawable.pleos_card_bg)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = 24 }
            // 카드 탭 → 상세 패널(원본값·감점·지원 여부)을 카드 아래에 펼친다.
            isClickable = true
            isFocusable = true
            descendantFocusability = LinearLayout.FOCUS_BLOCK_DESCENDANTS
            setOnClickListener {
                detail.visibility = if (detail.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
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
        title.isClickable = false
        state.isClickable = false
        detail.isClickable = false
        box.addView(title)
        box.addView(state)
        box.addView(detail)
        return box
    }

    /** 상세: 원본값·지원 여부·감점·이유·조치. */
    private fun detailText(category: Category, score: Int?, eval: Evaluation): String {
        val supported = score != null
        val deducted = if (score == null) 0 else 100 - score
        val reason = when {
            !supported -> "이 차가 값을 주지 않아 측정할 수 없어요."
            deducted == 0 -> "감점 없음."
            else -> "감점 $deducted 점."
        }
        val action = when {
            !supported -> ""
            deducted == 0 -> ""
            else -> "해결하면 관리 +5. " + getString(R.string.c19_disclaimer)
        }
        return buildString {
            append("지원: ").append(if (supported) "예" else "아니요").append(" · ")
            append(reason)
            if (action.isNotEmpty()) append(" ").append(action)
        }
    }

    /** 막대 하나: 카테고리 이름 + 점수. 높이는 점수에 비례(최소 8dp). */
    private fun trendBar(category: Category, score: Int?): View {
        val ctx = requireContext()
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        val value = TextView(ctx).apply {
            text = score?.toString() ?: "—"
            setTextAppearance(ai.pleos.ds.R.style.Pleos_Text_Label_S)
            setTextColor(ContextCompat.getColor(ctx, ai.pleos.ds.R.color.pleos_text_dark_secondary))
            gravity = android.view.Gravity.CENTER
        }
        val bar = View(ctx).apply {
            val h = ((score ?: 0).coerceIn(0, 100) * 1.6).toInt().coerceAtLeast(8)
            layoutParams = LinearLayout.LayoutParams(24.dp(ctx), h.dp(ctx)).also { it.bottomMargin = 8.dp(ctx) }
            setBackgroundColor(
                ContextCompat.getColor(
                    ctx,
                    when {
                        score == null -> ai.pleos.ds.R.color.pleos_text_dark_quaternary
                        score >= 100 -> ai.pleos.ds.R.color.pleos_informative_positive
                        score >= 70 -> ai.pleos.ds.R.color.pleos_regulation_yellow
                        else -> ai.pleos.ds.R.color.pleos_regulation_red
                    },
                ),
            )
        }
        val label = TextView(ctx).apply {
            text = category.label
            setTextAppearance(ai.pleos.ds.R.style.Pleos_Text_Label_S)
            setTextColor(ContextCompat.getColor(ctx, ai.pleos.ds.R.color.pleos_text_dark_tertiary))
            gravity = android.view.Gravity.CENTER
        }
        column.addView(value)
        column.addView(bar)
        column.addView(label)
        return column
    }

    private fun Int.dp(ctx: android.content.Context): Int =
        (this * ctx.resources.displayMetrics.density).toInt()

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
