package ai.moca.app.ui

import ai.moca.app.MainActivity
import ai.moca.app.MocaApp
import ai.moca.app.R
import ai.moca.app.inspect.Inspector
import ai.moca.app.score.ScoreBridge
import ai.moca.app.ui.model.CategoryLine
import ai.moca.app.ui.model.EnergyKind
import ai.moca.app.ui.model.StartPolicy
import ai.moca.app.ui.model.StartSummary
import ai.moca.app.ui.model.StartSummaryBuilder
import ai.moca.app.ui.model.WarningLine
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moca.core.Category
import moca.core.CategoryStatus
import java.util.Locale

/**
 * 앱 시작 화면(최종정리 3장). 앱을 새로 켤 때만 뜬다.
 * 점검 결과 ①~⑥을 보여 주고, 단추를 누르거나 1분이 지나면 메인으로 넘어간다.
 * 정차 중 빨간 경고등이 켜져 있으면 자동으로 넘어가지 않는다.
 */
class StartFragment : Fragment(R.layout.fragment_start) {

    private lateinit var startContent: View
    private lateinit var startGrade: TextView
    private lateinit var startLoading: TextView
    private lateinit var startUnmeasured: TextView
    private lateinit var startEnergy: TextView
    private lateinit var startWarnings: View
    private lateinit var startWarningsNone: TextView
    private lateinit var startCategories: View
    private lateinit var startActionsCard: View
    private lateinit var startActionRows: List<TextView>
    private lateinit var startActionsNone: TextView
    private lateinit var startAutoHint: TextView
    private lateinit var startGoRow: View
    private lateinit var startGoMain: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val advance = Runnable { goHome() }
    private var tts: TextToSpeech? = null
    private var categoryRows: List<CategoryRow> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        startContent = view.findViewById(R.id.startContent)
        startGrade = view.findViewById(R.id.startGrade)
        startLoading = view.findViewById(R.id.startLoading)
        startUnmeasured = view.findViewById(R.id.startUnmeasured)
        startEnergy = view.findViewById(R.id.startEnergy)
        startWarnings = view.findViewById(R.id.startWarnings)
        startWarningsNone = view.findViewById(R.id.startWarningsNone)
        startCategories = view.findViewById(R.id.startCategories)
        startActionsCard = view.findViewById(R.id.startActionsCard)
        startActionRows = listOf(
            view.findViewById(R.id.startAction1),
            view.findViewById(R.id.startAction2),
            view.findViewById(R.id.startAction3),
        )
        startActionsNone = view.findViewById(R.id.startActionsNone)
        startAutoHint = view.findViewById(R.id.startAutoHint)
        startGoRow = view.findViewById(R.id.startGoRow)
        startGoMain = view.findViewById(R.id.startGoMain)
        startGoMain.setOnClickListener { goHome() }
        categoryRows = listOf(
            CategoryRow(Category.POWER, view.findViewById(R.id.startCatPowerDot), view.findViewById(R.id.startCatPowerStatus), view.findViewById(R.id.startCatPowerScore)),
            CategoryRow(Category.BRAKE_STEERING, view.findViewById(R.id.startCatBrakeDot), view.findViewById(R.id.startCatBrakeStatus), view.findViewById(R.id.startCatBrakeScore)),
            CategoryRow(Category.TIRE, view.findViewById(R.id.startCatTireDot), view.findViewById(R.id.startCatTireStatus), view.findViewById(R.id.startCatTireScore)),
            CategoryRow(Category.ELECTRIC, view.findViewById(R.id.startCatElectricDot), view.findViewById(R.id.startCatElectricStatus), view.findViewById(R.id.startCatElectricScore)),
            CategoryRow(Category.SAFETY, view.findViewById(R.id.startCatSafetyDot), view.findViewById(R.id.startCatSafetyStatus), view.findViewById(R.id.startCatSafetyScore)),
        )

        val app = requireActivity().application as MocaApp
        startGrade.text = getString(R.string.home_grade_placeholder)
        startLoading.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                Inspector(app).run(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) renderNoData()
                return@launch
            }
            if (isAdded) render(result.engine, result.evaluation)
        }
    }

    private fun renderNoData() {
        startLoading.visibility = View.GONE
        if (!::startContent.isInitialized) return
        startContent.visibility = View.VISIBLE
        startGrade.text = getString(R.string.c20_not_enough_data)
    }

    private fun render(snapshot: moca.core.VehicleSnapshot, evaluation: moca.core.Evaluation?) {
        startLoading.visibility = View.GONE
        startContent.visibility = View.VISIBLE
        if (evaluation == null) {
            renderNoData()
            return
        }
        val s = StartSummaryBuilder.of(snapshot, evaluation)
        val activity = requireActivity() as MainActivity
        val driving = activity.isDrivingLimited()
        renderGrade(s)
        renderWarnings(s)
        renderCategories(s)
        renderActions(s)
        renderEnergy(s)
        renderUnmeasured(s)
        applyDriving(driving, s)
        scheduleAdvance(s, driving)
        speakIfNeeded(s, driving)
    }

    private fun renderGrade(s: StartSummary) {
        startGrade.text = if (s.gradeLabel != null && s.overall != null) {
            getString(R.string.start_grade, s.gradeLabel, s.overall)
        } else {
            getString(R.string.c20_not_enough_data)
        }
    }

    private fun renderWarnings(s: StartSummary) {
        val container = startWarnings as android.widget.LinearLayout
        container.removeAllViews()
        if (s.warnings.isEmpty()) {
            startWarningsNone.visibility = View.VISIBLE
            return
        }
        startWarningsNone.visibility = View.GONE
        for (line in s.warnings) {
            container.addView(warningRow(line))
        }
    }

    private fun warningRow(line: WarningLine): View {
        val ctx = requireContext()
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val pad = resources.getDimensionPixelSize(R.dimen.moca_gap_small)
            setPadding(0, pad, 0, 0)
        }
        val dot = View(ctx).apply {
            background = ctx.getDrawable(R.drawable.moca_status_dot)
            backgroundTintList = ColorStateList.valueOf(
                ctx.getColor(
                    if (line.red) ai.pleos.ds.R.color.pleos_regulation_red
                    else ai.pleos.ds.R.color.pleos_regulation_yellow,
                ),
            )
        }
        val size = resources.getDimensionPixelSize(R.dimen.moca_status_dot)
        row.addView(dot, android.widget.LinearLayout.LayoutParams(size, size))
        val label = TextView(ctx).apply {
            text = line.label
            setTextAppearance(ai.pleos.ds.R.style.Pleos_Text_Body_L)
            setTextColor(ctx.getColor(ai.pleos.ds.R.color.pleos_text_dark_primary))
        }
        val gap = resources.getDimensionPixelSize(R.dimen.moca_gap_small)
        row.addView(
            label,
            android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = gap },
        )
        return row
    }

    private fun renderCategories(s: StartSummary) {
        for (row in categoryRows) {
            val line = s.categories.firstOrNull { it.category == row.category } ?: continue
            renderCategory(row, line)
        }
    }

    private fun renderCategory(row: CategoryRow, line: CategoryLine) {
        val ctx = requireContext()
        val colorRes = when (line.status) {
            CategoryStatus.OK -> ai.pleos.ds.R.color.pleos_informative_positive
            CategoryStatus.CAUTION -> ai.pleos.ds.R.color.pleos_regulation_yellow
            CategoryStatus.DANGER -> ai.pleos.ds.R.color.pleos_regulation_red
            CategoryStatus.UNMEASURED -> ai.pleos.ds.R.color.pleos_text_dark_tertiary
        }
        row.dot.backgroundTintList = ColorStateList.valueOf(ctx.getColor(colorRes))
        row.status.text = line.status.label
        row.status.setTextColor(ctx.getColor(colorRes))
        row.score.text = line.score?.toString() ?: getString(R.string.start_score_none)
        row.score.setTextColor(
            ctx.getColor(
                if (line.status == CategoryStatus.DANGER) colorRes else ai.pleos.ds.R.color.pleos_text_dark_primary,
            ),
        )
    }

    private fun renderActions(s: StartSummary) {
        for ((index, row) in startActionRows.withIndex()) {
            val line = s.actions.getOrNull(index)
            if (line == null) {
                row.visibility = View.GONE
                continue
            }
            row.text = getString(R.string.start_action_line, line.label, line.points)
            row.visibility = View.VISIBLE
        }
        startActionsNone.visibility = if (s.actions.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun renderEnergy(s: StartSummary) {
        if (s.energy.isEmpty()) {
            startEnergy.visibility = View.GONE
            return
        }
        val fuel = s.energy.firstOrNull { it.kind == EnergyKind.FUEL }?.percent
        val battery = s.energy.firstOrNull { it.kind == EnergyKind.BATTERY }?.percent
        startEnergy.text = when {
            fuel != null && battery != null -> getString(R.string.start_energy_both, fuel, battery)
            fuel != null -> getString(R.string.start_energy_fuel, fuel)
            else -> getString(R.string.start_energy_battery, battery ?: 0)
        }
        startEnergy.visibility = View.VISIBLE
    }

    private fun renderUnmeasured(s: StartSummary) {
        startUnmeasured.visibility = if (s.unmeasured > 0) View.VISIBLE else View.GONE
        if (s.unmeasured > 0) {
            startUnmeasured.text = getString(R.string.start_unmeasured, s.unmeasured)
        }
    }

    /** 주행 중에는 ①②만 남긴다. 단추와 안내 줄은 숨긴다. */
    private fun applyDriving(driving: Boolean, s: StartSummary) {
        val onlyGradeAndWarnings = StartPolicy.showsOnlyGradeAndWarnings(driving)
        startCategories.visibility = if (onlyGradeAndWarnings) View.GONE else View.VISIBLE
        startActionsCard.visibility = if (onlyGradeAndWarnings) View.GONE else View.VISIBLE
        startEnergy.visibility =
            if (!onlyGradeAndWarnings && s.energy.isNotEmpty()) View.VISIBLE else View.GONE
        startUnmeasured.visibility =
            if (!onlyGradeAndWarnings && s.unmeasured > 0) View.VISIBLE else View.GONE
        startGoRow.visibility = if (onlyGradeAndWarnings) View.GONE else View.VISIBLE
        if (onlyGradeAndWarnings) {
            startAutoHint.visibility = View.GONE
            return
        }
        // 빨간 경고등 때문에 자동으로 넘어가지 않을 때는 그 이유를 보여 준다.
        if (StartPolicy.autoAdvances(s, driving)) {
            startAutoHint.text = getString(R.string.start_auto_hint)
        } else {
            startAutoHint.text = getString(R.string.c14_stop_safely)
        }
        startAutoHint.visibility = View.VISIBLE
    }

    private fun scheduleAdvance(s: StartSummary, driving: Boolean) {
        mainHandler.removeCallbacks(advance)
        if (driving) {
            mainHandler.postDelayed(advance, DRIVING_ADVANCE_MS)
            return
        }
        if (!StartPolicy.autoAdvances(s, driving)) return
        mainHandler.postDelayed(advance, StartPolicy.AUTO_ADVANCE_MS)
    }

    private fun speakIfNeeded(s: StartSummary, driving: Boolean) {
        if (!StartPolicy.speaksStopSafely(s, driving)) return
        setupTts()
    }

    private fun setupTts() {
        if (tts != null) return
        tts = TextToSpeech(requireContext()) { status ->
            val ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.KOREAN
                tts?.speak(
                    getString(R.string.c14_stop_safely),
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    TTS_UTTERANCE_ID,
                )
            }
        }
    }

    private fun goHome() {
        mainHandler.removeCallbacks(advance)
        (activity as? MainActivity)?.goHome()
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacks(advance)
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroyView()
    }

    private class CategoryRow(
        val category: Category,
        val dot: View,
        val status: TextView,
        val score: TextView,
    )

    private companion object {
        const val DRIVING_ADVANCE_MS = 3_000L
        const val TTS_UTTERANCE_ID = "moca-start-stop-safely"
    }
}
