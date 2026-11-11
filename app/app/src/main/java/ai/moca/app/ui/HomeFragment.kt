package ai.moca.app.ui

import ai.moca.app.MainActivity
import ai.moca.app.MocaApp
import ai.moca.app.R
import ai.moca.app.inspect.InspectionResult
import ai.moca.app.inspect.Inspector
import ai.moca.app.inspect.InspectPolicy
import ai.moca.app.link.FixLinks
import ai.moca.app.score.ScoreBridge
import ai.moca.app.ui.widget.ScoreRingView
import ai.moca.app.vehicle.WarningSummary
import ai.pleos.ds.PleosToast
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moca.core.Category
import moca.core.CategoryStatus
import moca.core.DailyRank
import moca.core.Evaluation
import moca.core.InspectionHistory
import moca.core.Priority

/**
 * 홈: 등급·고리, 가장 먼저 할 일, 카테고리 다섯 줄.
 * 점검은 이 화면이 처음 보일 때 한 번 돌고, 같은 키면 다시 기록하지 않는다.
 */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private lateinit var gradeValue: TextView
    private lateinit var gradeHint: TextView
    private lateinit var rankText: TextView
    private lateinit var warningSummary: TextView
    private lateinit var totalValue: TextView
    private lateinit var healthValue: TextView
    private lateinit var careValue: TextView
    private lateinit var lastInspection: TextView
    private lateinit var scoreRing: ScoreRingView
    private lateinit var gradeUpBanner: TextView
    private lateinit var topProblemText: TextView
    private lateinit var homeStreak: TextView
    private lateinit var topProblemAction: View
    private lateinit var rows: List<CatRow>
    private var drivingLimited = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        gradeValue = view.findViewById(R.id.gradeValue)
        gradeHint = view.findViewById(R.id.gradeHint)
        rankText = view.findViewById(R.id.rankText)
        rankText.isClickable = false
        rankText.isFocusable = false
        warningSummary = view.findViewById(R.id.warningSummary)
        totalValue = view.findViewById(R.id.totalValue)
        healthValue = view.findViewById(R.id.healthValue)
        careValue = view.findViewById(R.id.careValue)
        lastInspection = view.findViewById(R.id.lastInspection)
        scoreRing = view.findViewById(R.id.scoreRing)
        gradeUpBanner = view.findViewById(R.id.gradeUpBanner)
        topProblemText = view.findViewById(R.id.topProblemText)
        homeStreak = view.findViewById(R.id.homeStreak)
        topProblemAction = view.findViewById(R.id.topProblemAction)
        rows = listOf(
            catRow(view, Category.POWER, R.id.catPower, R.id.catPowerDot, R.id.catPowerStatus, R.id.catPowerScore),
            catRow(view, Category.BRAKE_STEERING, R.id.catBrake, R.id.catBrakeDot, R.id.catBrakeStatus, R.id.catBrakeScore),
            catRow(view, Category.TIRE, R.id.catTire, R.id.catTireDot, R.id.catTireStatus, R.id.catTireScore),
            catRow(view, Category.ELECTRIC, R.id.catElectric, R.id.catElectricDot, R.id.catElectricStatus, R.id.catElectricScore),
            catRow(view, Category.SAFETY, R.id.catSafety, R.id.catSafetyDot, R.id.catSafetyStatus, R.id.catSafetyScore),
        )
        val activity = requireActivity() as MainActivity
        drivingLimited = activity.isDrivingLimited()
        for (row in rows) {
            row.root.setOnClickListener {
                if (drivingLimited) return@setOnClickListener
                activity.goStatus(row.category)
            }
        }
        applyDriving()

        val app = requireActivity().application as MocaApp
        val cached = app.latestInspection
        val key = Inspector.currentKey(app)
        if (cached != null && !InspectPolicy.shouldRun(app.lastInspectKey, key)) {
            render(cached)
            return
        }
        gradeHint.text = getString(R.string.home_inspecting)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                Inspector(app).run(force = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) showInspectFailed()
                return@launch
            }
            if (isAdded) render(result)
        }
    }

    /** 주행 중에는 카테고리 줄만 막는다. 할 일 단추는 남긴다. */
    fun setDrivingLimited(limited: Boolean) {
        drivingLimited = limited
        if (view == null) return
        applyDriving()
        if (limited && ::scoreRing.isInitialized) {
            gradeUpBanner.visibility = View.GONE
            scoreRing.hold(scoreRing.progress)
        }
    }

    private fun applyDriving() {
        if (!::rows.isInitialized) return
        val clickable = !drivingLimited
        for (row in rows) {
            row.root.isClickable = clickable
            row.root.isFocusable = clickable
        }
    }

    private fun showInspectFailed() {
        gradeHint.text = ""
        renderRank(null)
        topProblemText.text = getString(R.string.c20_not_enough_data)
        topProblemAction.visibility = View.GONE
        homeStreak.visibility = View.GONE
        gradeUpBanner.visibility = View.GONE
        scoreRing.hold(0)
    }

    private fun render(result: InspectionResult) {
        val evaluation = result.evaluation
        renderGrade(evaluation)
        renderRank(evaluation)
        applyGradeMoment(result.demo, evaluation)
        renderWarnings(result.warnings)
        renderTop(result)
        renderCategories(evaluation)
        val saved = (requireActivity().application as MocaApp).prefs.inspectionLog(result.demo)
        lastInspection.text = InspectionHistory.homeLabel(saved, ScoreBridge.today())
    }

    private fun renderGrade(evaluation: Evaluation?) {
        val placeholder = getString(R.string.home_grade_placeholder)
        if (evaluation == null || evaluation.grade == null) {
            gradeValue.text = placeholder
            val notEnough = getString(R.string.c20_not_enough_data)
            val hint = if (evaluation != null && evaluation.categories.values.any { it != null }) {
                notEnough
            } else {
                getString(R.string.c03_first_grade)
            }
            gradeHint.text = if (hint == notEnough) "" else hint
            totalValue.text = getString(R.string.home_total_value, placeholder)
            healthValue.text = getString(R.string.home_health_value, placeholder)
            careValue.text = getString(R.string.home_care_value, placeholder)
            scoreRing.hasRed = evaluation?.hasRed == true
            return
        }
        gradeValue.text = evaluation.grade.label
        totalValue.text = getString(
            R.string.home_total_value,
            evaluation.displayedOverall?.toString() ?: placeholder,
        )
        healthValue.text = getString(
            R.string.home_health_value,
            evaluation.health?.toInt()?.toString() ?: placeholder,
        )
        careValue.text = getString(
            R.string.home_care_value,
            evaluation.care?.toString() ?: placeholder,
        )
        gradeHint.text = when {
            evaluation.redCapNotice != null -> evaluation.redCapNotice
            evaluation.nextGrade != null && evaluation.pointsToNext != null ->
                getString(R.string.c04_next_grade, evaluation.nextGrade.label, evaluation.pointsToNext)
            else -> getString(R.string.c03_first_grade)
        }
        scoreRing.hasRed = evaluation.hasRed
    }

    /**
     * 지난번에 본 등급보다 지금이 높고 정차 중이면 한 줄을 보이고 고리를 한 번 채운다.
     * 비교한 뒤에는 지금 값을 저장한다. 주행 중에는 고리가 움직이지 않는다.
     */
    private fun applyGradeMoment(demo: Boolean, evaluation: Evaluation?) {
        val app = requireActivity().application as MocaApp
        val seen = app.prefs.seen(demo)
        val nowGrade = evaluation?.grade
        val nowRank = if (evaluation == null || nowGrade == null) {
            null
        } else {
            DailyRank.board(app.prefs.nickname, evaluation)?.rank
        }
        val nowScore = evaluation?.displayedOverall
        val moment = GradeMoment.decide(seen.grade, seen.rank, nowGrade, nowRank, drivingLimited)
        app.prefs.rememberSeen(demo, nowGrade?.label, nowRank, nowScore)
        val score = nowScore ?: 0
        if (moment == null) {
            gradeUpBanner.visibility = View.GONE
            scoreRing.hold(score)
            return
        }
        gradeUpBanner.visibility = View.VISIBLE
        gradeUpBanner.text = moment.text
        val from = seen.score ?: score
        scoreRing.play(from, score)
    }

    /** 등급이 없으면 순위를 숨긴다. 줄은 누르지 않는다. 이동은 아래 탭뿐이다. */
    private fun renderRank(evaluation: Evaluation?) {
        val board = if (evaluation?.grade == null) {
            null
        } else {
            val app = requireActivity().application as MocaApp
            DailyRank.board(app.prefs.nickname, evaluation)
        }
        if (board == null) {
            rankText.visibility = View.GONE
            return
        }
        rankText.visibility = View.VISIBLE
        rankText.text = getString(R.string.home_rank_line, board.rank, board.total)
        rankText.isClickable = false
    }

    private fun renderWarnings(summary: WarningSummary) {
        warningSummary.text = if (summary.total == 0) {
            getString(R.string.home_warning_none)
        } else {
            getString(R.string.home_warning_count, summary.total)
        }
        val colorRes = when {
            summary.redCount > 0 -> ai.pleos.ds.R.color.pleos_regulation_red
            summary.yellowCount > 0 -> ai.pleos.ds.R.color.pleos_regulation_yellow
            else -> ai.pleos.ds.R.color.pleos_text_dark_primary
        }
        warningSummary.setTextColor(requireContext().getColor(colorRes))
    }

    private fun renderTop(result: InspectionResult) {
        val evaluation = result.evaluation
        if (evaluation == null || evaluation.grade == null) {
            topProblemText.text = getString(R.string.c20_not_enough_data)
            renderStreak(result)
            topProblemAction.visibility = View.GONE
            return
        }
        val problem = Priority.topProblem(result.engine)
        if (problem == null) {
            topProblemText.text = getString(R.string.home_all_good)
            renderStreak(result)
            topProblemAction.visibility = View.GONE
            return
        }
        homeStreak.visibility = View.GONE
        topProblemText.text = getString(
            R.string.home_problem_line,
            problem.label,
            problem.category.label,
            problem.points,
        )
        val action = Priority.actionFor(problem, result.engine)
        val button = topProblemAction as TextView
        button.visibility = View.VISIBLE
        button.text = action.label
        button.setOnClickListener {
            val host = activity as? MainActivity ?: return@setOnClickListener
            FixLinks.start(host, action, host.isDrivingLimited()) { linkResult ->
                PleosToast.show(host, linkResult.message)
            }
        }
    }

    /** 급한 문제가 없을 때만 부른다. 등급을 못 내는 차도 점검 기록은 쌓이므로 같이 보여 준다. */
    private fun renderStreak(result: InspectionResult) {
        val streak = InspectionHistory.streakDays(
            (requireActivity().application as MocaApp).prefs.inspectionLog(result.demo),
            ScoreBridge.today(),
        )
        homeStreak.visibility = if (streak >= 1) View.VISIBLE else View.GONE
        if (streak >= 1) homeStreak.text = getString(R.string.home_streak, streak)
    }

    private fun renderCategories(evaluation: Evaluation?) {
        for (row in rows) {
            val score = evaluation?.categories?.get(row.category)
            val status = CategoryStatus.of(score)
            row.status.text = status.label
            row.score.text = score?.toString() ?: getString(R.string.home_grade_placeholder)
            val colorRes = when (status) {
                CategoryStatus.OK -> ai.pleos.ds.R.color.pleos_informative_positive
                CategoryStatus.CAUTION -> ai.pleos.ds.R.color.pleos_regulation_yellow
                CategoryStatus.DANGER -> ai.pleos.ds.R.color.pleos_informative_negative
                CategoryStatus.UNMEASURED -> ai.pleos.ds.R.color.pleos_text_dark_tertiary
            }
            row.dot.backgroundTintList = ColorStateList.valueOf(requireContext().getColor(colorRes))
        }
    }

    private fun catRow(view: View, category: Category, rowId: Int, dotId: Int, statusId: Int, scoreId: Int): CatRow =
        CatRow(
            category = category,
            root = view.findViewById(rowId),
            dot = view.findViewById(dotId),
            status = view.findViewById(statusId),
            score = view.findViewById(scoreId),
        )

    private class CatRow(
        val category: Category,
        val root: View,
        val dot: View,
        val status: TextView,
        val score: TextView,
    )
}
