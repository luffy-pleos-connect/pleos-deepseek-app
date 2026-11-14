package ai.moca.app.ui

import ai.moca.app.MainActivity
import ai.moca.app.MocaApp
import ai.moca.app.R
import ai.moca.app.inspect.InspectionResult
import ai.moca.app.inspect.Inspector
import ai.moca.app.ui.model.Solution
import ai.moca.app.ui.model.SolutionButton
import ai.moca.app.ui.model.SolutionKind
import ai.moca.app.ui.model.SolutionText
import ai.moca.app.ui.model.Solutions
import ai.moca.app.ui.model.StatusOrder
import ai.moca.app.ui.widget.CarDiagramView
import ai.moca.app.ui.widget.TrendGraphView
import ai.moca.app.nav.PlaceKind
import ai.moca.app.score.ScoreBridge
import ai.pleos.ds.PleosToast
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moca.core.Category
import moca.core.CategoryStatus
import moca.core.InspectionHistory
import moca.core.Profile

/**
 * 상태관리 (최종정리 5장).
 * 왼쪽: 등급·관리 점수, 2D 자동차 그림, [다시 점검], (데모 D3) [ABS 해결 (데모)], 추이.
 * 오른쪽: 구역 카드 5개. 점수가 낮은 구역이 위. 카드를 누르면 그 카드만 펼쳐진다.
 * 그림의 영역을 눌러도 그 구역 카드가 펼쳐진다.
 */
class StatusFragment : Fragment(R.layout.fragment_status) {

    val requestedCategory: String?
        get() = arguments?.getString(ARG_CATEGORY)

    private lateinit var gradeText: TextView
    private lateinit var careText: TextView
    private lateinit var carDiagram: CarDiagramView
    private lateinit var reinspectButton: Button
    private lateinit var resolveAbsButton: Button
    private lateinit var trendGraph: TrendGraphView
    private lateinit var trendEmpty: TextView
    private lateinit var cardsColumn: LinearLayout

    private lateinit var cards: Map<Category, CardHolder>
    private var inspection: InspectionResult? = null
    private var selected: Category? = null
    private var busy = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        gradeText = view.findViewById(R.id.statusGrade)
        careText = view.findViewById(R.id.statusCare)
        carDiagram = view.findViewById(R.id.carDiagram)
        reinspectButton = view.findViewById(R.id.buttonReinspect)
        resolveAbsButton = view.findViewById(R.id.buttonResolveAbs)
        trendGraph = view.findViewById(R.id.trendGraph)
        trendEmpty = view.findViewById(R.id.trendEmpty)
        cardsColumn = view.findViewById(R.id.statusCards)

        cards = Category.entries.associateWith { category -> createCard(category) }
        carDiagram.onZoneClick = { category -> openCard(category) }
        reinspectButton.setOnClickListener { reinspect() }
        resolveAbsButton.setOnClickListener { resolveAbs() }

        val app = requireActivity().application as MocaApp
        val cached = app.latestInspection
        if (cached != null) {
            bind(cached)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                Inspector(app).run(force = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isAdded) showUnread()
                return@launch
            }
            if (isAdded) bind(result)
        }
    }

    private fun bind(result: InspectionResult) {
        inspection = result
        if (selected == null) selected = initialCategory(result)
        val evaluation = result.evaluation
        val categories = evaluation?.categories.orEmpty()
        val statuses = Category.entries.associateWith { CategoryStatus.of(categories[it]) }

        gradeText.text = if (evaluation?.grade != null && evaluation.displayedOverall != null) {
            getString(R.string.main_grade, evaluation.grade!!.label, evaluation.displayedOverall!!)
        } else {
            getString(R.string.home_grade_placeholder)
        }
        careText.text = evaluation?.care?.let { getString(R.string.status_care, it) }.orEmpty()
        carDiagram.render(statuses, result.engine.lowTireWheels)
        resolveAbsButton.visibility = if (canResolveAbs(result)) View.VISIBLE else View.GONE
        renderCards(result, statuses)
        renderTrend(result)
    }

    private fun initialCategory(result: InspectionResult): Category {
        val requested = Category.entries.firstOrNull { it.name == requestedCategory }
        if (requested != null) return requested
        val order = orderOf(result)
        return order.firstOrNull { result.evaluation?.categories?.get(it) != null } ?: order.first()
    }

    private fun orderOf(result: InspectionResult): List<Category> {
        val evaluation = result.evaluation ?: return Category.entries.toList()
        return StatusOrder.of(evaluation)
    }

    // ── 오른쪽 카드 ─────────────────────────────────────────────────

    private fun createCard(category: Category): CardHolder {
        val root = layoutInflater.inflate(R.layout.item_status_card, cardsColumn, false)
        val holder = CardHolder(
            category = category,
            root = root,
            dot = root.findViewById(R.id.cardDot),
            title = root.findViewById(R.id.cardTitle),
            status = root.findViewById(R.id.cardStatus),
            score = root.findViewById(R.id.cardScore),
            detail = root.findViewById(R.id.cardDetail),
        )
        holder.title.text = labelOf(category)
        holder.root.setOnClickListener { openCard(category) }
        return holder
    }

    private fun renderCards(result: InspectionResult, statuses: Map<Category, CategoryStatus>) {
        val chosen = selected
        cardsColumn.removeAllViews()
        for (category in orderOf(result)) {
            val holder = cards[category] ?: continue
            val score = result.evaluation?.categories?.get(category)
            val status = statuses.getValue(category)
            holder.status.text = status.label
            holder.score.text = score?.toString() ?: getString(R.string.home_grade_placeholder)
            val color = ContextCompat.getColor(requireContext(), colorOf(status))
            holder.dot.backgroundTintList = ColorStateList.valueOf(color)
            holder.status.setTextColor(color)
            holder.root.isSelected = category == chosen
            holder.detail.removeAllViews()
            holder.detail.visibility = if (category == chosen) View.VISIBLE else View.GONE
            cardsColumn.addView(holder.root)
            if (category == chosen) fillDetail(category, result)
        }
    }

    private fun openCard(category: Category) {
        selected = category
        val result = inspection ?: return
        val statuses = Category.entries.associateWith { CategoryStatus.of(result.evaluation?.categories?.get(it)) }
        renderCards(result, statuses)
    }

    private fun fillDetail(category: Category, result: InspectionResult) {
        val holder = cards[category] ?: return
        holder.detail.removeAllViews()
        val score = result.evaluation?.categories?.get(category)
        if (score == null) {
            holder.detail.addView(message(getString(R.string.status_zone_unreadable)))
            holder.detail.addView(disclaimer())
            return
        }
        val solutions = Solutions.of(result.engine, category)
        if (solutions.isEmpty()) {
            holder.detail.addView(message(getString(R.string.status_no_solution)))
            holder.detail.addView(disclaimer())
            return
        }
        for (solution in solutions) {
            holder.detail.addView(solutionView(solution, result))
        }
        holder.detail.addView(disclaimer())
    }

    private fun message(text: String): TextView = TextView(requireContext()).apply {
        setText(text)
        setTextAppearance(ai.pleos.ds.R.style.Pleos_Text_Body_L)
        setTextColor(ContextCompat.getColor(context, ai.pleos.ds.R.color.pleos_text_dark_secondary))
        setPadding(0, resources.getDimensionPixelSize(R.dimen.moca_gap_small), 0, 0)
    }

    private fun disclaimer(): TextView = TextView(requireContext()).apply {
        setText(R.string.c19_disclaimer)
        setTextAppearance(ai.pleos.ds.R.style.Pleos_Text_Label_M)
        setTextColor(ContextCompat.getColor(context, ai.pleos.ds.R.color.pleos_text_dark_tertiary))
        setPadding(0, resources.getDimensionPixelSize(R.dimen.moca_gap), 0, 0)
    }

    private fun solutionView(solution: Solution, result: InspectionResult): View {
        val root = layoutInflater.inflate(R.layout.item_solution, null, false)
        root.findViewById<TextView>(R.id.solutionLabel).text = solution.label
        root.findViewById<TextView>(R.id.solutionPoints).text =
            getString(R.string.status_solution_points, solution.points)
        root.findViewById<TextView>(R.id.solutionKinds).text =
            solution.kinds.joinToString(" · ") { getString(kindLabel(it)) }

        // 빨간 경고등은 안내 문구 자체가 정차 안내다(c14_stop_safely). 같은 줄을 두 번 쓰지 않는다.
        val stopFirst = root.findViewById<TextView>(R.id.solutionStopFirst)
        val showStopFirst = solution.stopFirst && solution.text != SolutionText.WARNING_RED
        stopFirst.visibility = if (showStopFirst) View.VISIBLE else View.GONE

        val text = root.findViewById<TextView>(R.id.solutionText)
        text.setText(textOf(solution.text))
        val second = root.findViewById<TextView>(R.id.solutionSecond)
        if (solution.text == SolutionText.WARNING_RED) {
            second.setText(R.string.solution_text_warning_red_second)
            second.visibility = View.VISIBLE
        } else {
            second.visibility = View.GONE
        }

        val qrRow = root.findViewById<View>(R.id.qrRow)
        val qrImage = root.findViewById<ImageView>(R.id.qrImage)
        when {
            solution.buttons.contains(SolutionButton.QR_INFLATOR) -> {
                qrRow.visibility = View.VISIBLE
                qrImage.setImageResource(R.drawable.qr_tire_inflator)
                qrImage.contentDescription = getString(R.string.status_qr_inflator_description)
            }
            solution.buttons.contains(SolutionButton.QR_WASHER) -> {
                qrRow.visibility = View.VISIBLE
                qrImage.setImageResource(R.drawable.qr_washer_fluid)
                qrImage.contentDescription = getString(R.string.status_qr_washer_description)
            }
            else -> qrRow.visibility = View.GONE
        }

        val buttons = root.findViewById<LinearLayout>(R.id.solutionButtons)
        val repairActions = mutableListOf<Button>()
        for (button in solution.buttons) {
            val kind = placeKindOf(button) ?: continue
            val view = layoutInflater.inflate(R.layout.item_solution_button, buttons, false) as Button
            view.setText(labelOf(button))
            view.setOnClickListener { navigate(kind, root) }
            buttons.addView(view)
            repairActions += view
        }
        buttons.visibility = if (buttons.childCount == 0) View.GONE else View.VISIBLE

        root.findViewById<TextView>(R.id.solutionBonus).text =
            getString(R.string.c07_bonus_fixed, solution.label)

        root.findViewById<View>(R.id.solutionAsk).setOnClickListener {
            val host = activity as? MainActivity ?: return@setOnClickListener
            host.openVoiceWithItem(voiceArgs(solution))
        }
        return root
    }

    private fun navigate(kind: PlaceKind, solutionRoot: View) {
        val host = activity as? MainActivity ?: return
        val app = requireActivity().application as MocaApp
        val naviResult = app.navi.navigateTo(kind, demo = app.prefs.demoEnabled)
        val row = solutionRoot.findViewById<View>(R.id.solutionResultRow)
        val text = solutionRoot.findViewById<TextView>(R.id.solutionResult)
        row.visibility = View.VISIBLE
        text.text = naviResult.message
        if (!naviResult.ok) PleosToast.show(host, naviResult.message)
    }

    private fun voiceArgs(solution: Solution): Bundle = Bundle().apply {
        putString(VoiceFragment.ARG_ITEM_CATEGORY, labelOf(solution.category))
        putString(VoiceFragment.ARG_ITEM_GROUP, solution.group)
        putString(VoiceFragment.ARG_ITEM_LABEL, solution.label)
        putInt(VoiceFragment.ARG_ITEM_POINTS, solution.points)
        putString(
            VoiceFragment.ARG_ITEM_KINDS,
            solution.kinds.joinToString(",") { getString(kindLabel(it)) },
        )
    }

    // ── 왼쪽 ────────────────────────────────────────────────────────

    private fun reinspect() {
        if (busy) return
        val app = requireActivity().application as MocaApp
        busy = true
        reinspectButton.isEnabled = false
        reinspectButton.setText(R.string.status_reinspect_loading)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                Inspector(app).run(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            busy = false
            if (!isAdded) return@launch
            reinspectButton.isEnabled = true
            reinspectButton.setText(R.string.status_reinspect)
            if (result != null) bind(result)
        }
    }

    /** 데모 D3 에서 ABS 빨간 경고등이 지금도 켜져 있을 때만 보인다. */
    private fun canResolveAbs(result: InspectionResult): Boolean {
        val app = requireActivity().application as MocaApp
        if (!result.demo || !app.prefs.demoEnabled) return false
        if (app.prefs.demoScenarioId != SCENARIO_D3) return false
        return result.engine.telltales.any { it.active && it.telltale == moca.core.Telltale.ABS && it.color == moca.core.Color.RED }
    }

    private fun resolveAbs() {
        val app = requireActivity().application as MocaApp
        val host = activity as? MainActivity ?: return
        app.store.demoResolveD3(ScoreBridge.today())
        val result = Inspector.stored(app)
        if (result == null) {
            PleosToast.show(host, getString(R.string.status_resolve_abs_failed))
            return
        }
        app.latestInspection = result
        app.lastInspectKey = Inspector.currentKey(app)
        selected = Category.BRAKE_STEERING
        bind(result)
    }

    private fun renderTrend(result: InspectionResult) {
        val app = requireActivity().application as MocaApp
        val today = ScoreBridge.today()
        val profile = if (result.demo) Profile.DEMO else Profile.REAL
        val points = InspectionHistory.trendPoints(app.store.records(profile), profile, today)
        // 점수를 못 낸 날만 있으면 그릴 선이 없다.
        if (points.count { it.overall != null } < 2) {
            trendGraph.visibility = View.GONE
            trendEmpty.visibility = View.VISIBLE
            return
        }
        trendEmpty.visibility = View.GONE
        trendGraph.visibility = View.VISIBLE
        trendGraph.points = points
    }

    private fun showUnread() {
        gradeText.text = getString(R.string.home_grade_placeholder)
        careText.text = ""
        carDiagram.render(emptyMap(), emptySet())
        resolveAbsButton.visibility = View.GONE
        cardsColumn.removeAllViews()
        cardsColumn.addView(message(getString(R.string.c06_unmeasurable)))
        trendGraph.visibility = View.GONE
        trendEmpty.visibility = View.VISIBLE
    }

    // ── 이름·색 ─────────────────────────────────────────────────────

    private fun labelOf(category: Category): String = when (category) {
        Category.POWER -> getString(R.string.home_cat_power)
        Category.BRAKE_STEERING -> getString(R.string.home_cat_brake)
        Category.TIRE -> getString(R.string.home_cat_tire)
        Category.ELECTRIC -> getString(R.string.home_cat_electric)
        Category.SAFETY -> getString(R.string.home_cat_safety)
    }

    private fun colorOf(status: CategoryStatus): Int = when (status) {
        CategoryStatus.OK -> ai.pleos.ds.R.color.pleos_informative_positive
        CategoryStatus.CAUTION -> ai.pleos.ds.R.color.pleos_regulation_yellow
        CategoryStatus.DANGER -> ai.pleos.ds.R.color.pleos_regulation_red
        CategoryStatus.UNMEASURED -> ai.pleos.ds.R.color.pleos_text_dark_tertiary
    }

    private fun kindLabel(kind: SolutionKind): Int = when (kind) {
        SolutionKind.REPAIR_SHOP -> R.string.status_solution_kind_repair_shop
        SolutionKind.BUY -> R.string.status_solution_kind_buy
        SolutionKind.SELF -> R.string.status_solution_kind_self
    }

    private fun textOf(text: SolutionText): Int = when (text) {
        SolutionText.WARNING_RED -> R.string.c14_stop_safely
        SolutionText.WARNING_YELLOW -> R.string.solution_text_warning_yellow
        SolutionText.ENGINE_OIL_LOW -> R.string.solution_text_engine_oil_low
        SolutionText.ENGINE_OIL_HIGH -> R.string.solution_text_engine_oil_high
        SolutionText.TRANSMISSION_OIL -> R.string.solution_text_transmission_oil
        SolutionText.BRAKE_FLUID -> R.string.solution_text_brake_fluid
        SolutionText.AIRBAG -> R.string.solution_text_airbag
        SolutionText.TIRE_LOW -> R.string.solution_text_tire_low
        SolutionText.WASHER -> R.string.solution_text_washer
        SolutionText.FUEL_LOW -> R.string.solution_text_fuel_low
        SolutionText.BATTERY_LOW -> R.string.solution_text_battery_low
        SolutionText.ENERGY_BOTH_LOW -> R.string.solution_text_energy_both_low
    }

    private fun labelOf(button: SolutionButton): Int = when (button) {
        SolutionButton.FIND_REPAIR -> R.string.status_button_find_repair
        SolutionButton.GAS_STATION -> R.string.status_button_gas_station
        SolutionButton.CHARGING_STATION -> R.string.status_button_charging_station
        SolutionButton.QR_INFLATOR, SolutionButton.QR_WASHER -> R.string.status_button_find_repair
    }

    /** 내비로 갈 단추만 장소 종류가 있다. QR 은 단추가 아니라 그림이다. */
    private fun placeKindOf(button: SolutionButton): PlaceKind? = when (button) {
        SolutionButton.FIND_REPAIR -> PlaceKind.REPAIR_SHOP
        SolutionButton.GAS_STATION -> PlaceKind.GAS_STATION
        SolutionButton.CHARGING_STATION -> PlaceKind.CHARGING_STATION
        SolutionButton.QR_INFLATOR, SolutionButton.QR_WASHER -> null
    }

    private class CardHolder(
        val category: Category,
        val root: View,
        val dot: View,
        val title: TextView,
        val status: TextView,
        val score: TextView,
        val detail: LinearLayout,
    )

    companion object {
        const val ARG_CATEGORY = "category"
        private const val SCENARIO_D3 = "D3"
    }
}
