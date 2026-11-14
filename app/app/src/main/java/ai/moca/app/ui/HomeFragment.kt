package ai.moca.app.ui

import ai.moca.app.MainActivity
import ai.moca.app.MocaApp
import ai.moca.app.R
import ai.moca.app.inspect.InspectionResult
import ai.moca.app.inspect.Inspector
import ai.moca.app.ui.model.AgoLabel
import ai.moca.app.ui.model.BadgeTone
import ai.moca.app.ui.model.WarningBadges
import ai.moca.app.vehicle.WarningSummary
import ai.moca.app.vehicle.VehicleSource
import ai.pleos.ds.PleosPopup
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moca.core.Evaluation

/**
 * 메인: 등급·경고 배지·점검 시각 한 줄, 기능 단추 3개(최종정리 4장).
 * 홈은 차를 다시 읽지 않는다. 최근 점검 결과가 없을 때만 한 번 읽는다.
 */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private lateinit var mainGrade: TextView
    private lateinit var mainBadge: TextView
    private lateinit var mainAgo: TextView
    private lateinit var homeNoGradeCard: View
    private lateinit var homeDemoAction: Button
    private lateinit var buttonStatus: View
    private lateinit var buttonVoice: View
    private lateinit var buttonBattle: View

    private val mainHandler = Handler(Looper.getMainLooper())
    private val agoTick = object : Runnable {
        override fun run() {
            renderAgo()
            mainHandler.postDelayed(this, AGO_TICK_MS)
        }
    }
    private var inspectedAtMillis: Long = 0L
    private var hasGrade = false
    private var watching: VehicleSource? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mainGrade = view.findViewById(R.id.mainGrade)
        mainBadge = view.findViewById(R.id.mainBadge)
        mainAgo = view.findViewById(R.id.mainAgo)
        homeNoGradeCard = view.findViewById(R.id.homeNoGradeCard)
        homeDemoAction = view.findViewById(R.id.homeDemoAction)
        buttonStatus = view.findViewById(R.id.buttonStatus)
        buttonVoice = view.findViewById(R.id.buttonVoice)
        buttonBattle = view.findViewById(R.id.buttonBattle)

        homeDemoAction.setOnClickListener { enableDemoAndInspect() }
        buttonStatus.setOnClickListener { openFeature(FEATURE_STATUS) }
        buttonVoice.setOnClickListener {
            (requireActivity() as MainActivity).goVoice()
        }
        buttonBattle.setOnClickListener { openFeature(FEATURE_BATTLE) }

        val app = requireActivity().application as MocaApp
        val latest = app.latestInspection ?: Inspector.stored(app)
        if (latest != null) {
            render(latest)
        } else {
            viewLifecycleOwner.lifecycleScope.launch {
                val result = try {
                    Inspector(app).run(force = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@launch
                }
                if (isAdded) render(result)
            }
        }
    }

    private fun render(result: InspectionResult) {
        inspectedAtMillis = result.atMillis
        renderGrade(result.evaluation)
        renderWarnings(result.warnings)
        renderAgo()
        watchWarnings()
    }

    private fun renderGrade(evaluation: Evaluation?) {
        val grade = evaluation?.grade
        val overall = evaluation?.displayedOverall
        hasGrade = grade != null && overall != null
        mainGrade.text = if (hasGrade) {
            getString(R.string.main_grade, grade!!.label, overall!!)
        } else {
            getString(R.string.home_grade_placeholder)
        }
        homeNoGradeCard.visibility = if (hasGrade) View.GONE else View.VISIBLE
        // 등급이 없으면 「데모로 보기」만 남긴다. 이미 데모면 켤 것이 없다.
        val app = requireActivity().application as MocaApp
        homeDemoAction.visibility = if (!hasGrade && !app.prefs.demoEnabled) View.VISIBLE else View.GONE
    }

    private fun renderWarnings(summary: WarningSummary) {
        val badge = WarningBadges.of(summary)
        mainBadge.text = badge.text
        val colorRes = when (badge.tone) {
            BadgeTone.RED -> ai.pleos.ds.R.color.pleos_regulation_red
            BadgeTone.YELLOW -> ai.pleos.ds.R.color.pleos_regulation_yellow
            BadgeTone.NONE -> ai.pleos.ds.R.color.pleos_text_dark_primary
        }
        mainBadge.setTextColor(requireContext().getColor(colorRes))
    }

    private fun renderAgo() {
        if (!::mainAgo.isInitialized || inspectedAtMillis <= 0L) return
        mainAgo.text = AgoLabel.of(inspectedAtMillis, System.currentTimeMillis())
    }

    /** 경고등이 바뀌면 배지를 바로 다시 그린다. 화면을 떠날 때 리스너를 뗀다. */
    private fun watchWarnings() {
        val app = requireActivity().application as MocaApp
        if (watching === app.vehicle()) return
        watching?.stopWatchingWarnings()
        val source = app.vehicle()
        watching = source
        source.startWatchingWarnings { summary ->
            if (isAdded && view != null) renderWarnings(summary)
        }
    }

    private fun openFeature(feature: Int) {
        val activity = requireActivity() as MainActivity
        if (activity.isDrivingLimited()) {
            showDrivingLimited()
            return
        }
        if (feature == FEATURE_BATTLE && !hasGrade) {
            PleosPopup.Builder(requireActivity())
                .description(getString(R.string.main_battle_no_grade))
                .confirm(getString(R.string.common_confirm))
                .show()
            return
        }
        when (feature) {
            FEATURE_STATUS -> activity.goStatus()
            FEATURE_BATTLE -> activity.goBattle()
        }
    }

    /** 주행 중에는 상태관리·대전하기로 들어가지 않고, 음성으로 묻는 길만 알려 준다. */
    private fun showDrivingLimited() {
        PleosPopup.Builder(requireActivity())
            .description(getString(R.string.c08_driving_limited))
            .confirm(getString(R.string.c09_ask_by_voice)) {
                (activity as? MainActivity)?.goVoice()
            }
            .cancel(getString(R.string.common_close)) { }
            .show()
    }

    /** 설정의 데모 스위치를 켰을 때와 같은 일을 한다: 데모 켜기 → 다시 점검 → 화면 새로 그리기. */
    private fun enableDemoAndInspect() {
        val app = requireActivity().application as MocaApp
        val host = activity as? MainActivity ?: return
        if (host.isDrivingLimited()) return
        DemoMode.turnOn(app.prefs, DemoMode.HOME_SCENARIO)
        app.latestInspection = null
        app.lastInspectKey = null
        homeDemoAction.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Inspector(app).run(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) homeDemoAction.isEnabled = true
                return@launch
            }
            if (isAdded) host.recreate()
        }
    }

    override fun onResume() {
        super.onResume()
        mainHandler.removeCallbacks(agoTick)
        mainHandler.post(agoTick)
    }

    override fun onPause() {
        mainHandler.removeCallbacks(agoTick)
        super.onPause()
    }

    override fun onDestroyView() {
        mainHandler.removeCallbacks(agoTick)
        watching?.stopWatchingWarnings()
        watching = null
        super.onDestroyView()
    }

    private companion object {
        const val AGO_TICK_MS = 30_000L
        const val FEATURE_STATUS = 0
        const val FEATURE_BATTLE = 1
    }
}
