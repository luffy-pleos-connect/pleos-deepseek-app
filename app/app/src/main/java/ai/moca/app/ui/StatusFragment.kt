package ai.moca.app.ui

import ai.moca.app.MainActivity
import ai.moca.app.MocaApp
import ai.moca.app.R
import ai.moca.app.inspect.InspectionResult
import ai.moca.app.inspect.Inspector
import ai.moca.app.link.FixLinks
import ai.moca.app.score.ScoreBridge
import ai.moca.app.ui.widget.TrendGraphView
import ai.moca.app.vehicle.ItemReading
import ai.moca.app.vehicle.PleosVehicleSource
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
import moca.core.Color
import moca.core.Deduction
import moca.core.Demo
import moca.core.FluidLevel
import moca.core.InspectionHistory
import moca.core.Item
import moca.core.Priority
import moca.core.Profile
import moca.core.Rules
import moca.core.Telltale
import moca.core.WheelArea
import moca.core.VehicleSnapshot

/**
 * 상세: 왼쪽 카테고리 다섯 줄, 오른쪽은 고른 카테고리의 값·감점·조치·추이.
 * 값은 읽은 것만 보여 준다. 못 읽은 항목과 지원하지 않는 항목을 구분한다.
 */
class StatusFragment : Fragment(R.layout.fragment_status) {

    val requestedCategory: String?
        get() = arguments?.getString(ARG_CATEGORY)

    private lateinit var rows: List<CatRow>
    private lateinit var detailTitle: TextView
    private lateinit var detailStatus: TextView
    private lateinit var detailValues: TextView
    private lateinit var detailDeductions: TextView
    private lateinit var detailAdvice: TextView
    private lateinit var detailAdviceLabel: TextView
    private lateinit var detailAction: TextView
    private lateinit var trendGraph: TrendGraphView
    private lateinit var trendEmpty: TextView
    private var inspection: InspectionResult? = null
    private var selected: Category? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rows = listOf(
            catRow(view, Category.POWER, R.id.statusPower, R.id.statusPowerDot, R.id.statusPowerStatus, R.id.statusPowerScore),
            catRow(view, Category.BRAKE_STEERING, R.id.statusBrake, R.id.statusBrakeDot, R.id.statusBrakeStatus, R.id.statusBrakeScore),
            catRow(view, Category.TIRE, R.id.statusTire, R.id.statusTireDot, R.id.statusTireStatus, R.id.statusTireScore),
            catRow(view, Category.ELECTRIC, R.id.statusElectric, R.id.statusElectricDot, R.id.statusElectricStatus, R.id.statusElectricScore),
            catRow(view, Category.SAFETY, R.id.statusSafety, R.id.statusSafetyDot, R.id.statusSafetyStatus, R.id.statusSafetyScore),
        )
        detailTitle = view.findViewById(R.id.detailTitle)
        detailStatus = view.findViewById(R.id.detailStatus)
        detailValues = view.findViewById(R.id.detailValues)
        detailDeductions = view.findViewById(R.id.detailDeductions)
        detailAdvice = view.findViewById(R.id.detailAdvice)
        detailAdviceLabel = view.findViewById(R.id.detailAdviceLabel)
        detailAction = view.findViewById(R.id.detailAction)
        trendGraph = view.findViewById(R.id.trendGraph)
        trendEmpty = view.findViewById(R.id.trendEmpty)
        for (row in rows) {
            row.root.setOnClickListener {
                val current = inspection ?: return@setOnClickListener
                selected = row.category
                renderRows(current)
                renderDetail(current)
            }
        }

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
        if (selected == null) selected = initialCategory(result.engine)
        renderRows(result)
        renderDetail(result)
        renderTrend(result)
    }

    private fun initialCategory(snapshot: VehicleSnapshot): Category {
        val requested = Category.entries.firstOrNull { it.name == requestedCategory }
        if (requested != null) return requested
        return Priority.topProblem(snapshot)?.category ?: Category.POWER
    }

    private fun renderRows(result: InspectionResult) {
        val chosen = selected
        for (row in rows) {
            val score = result.evaluation?.categories?.get(row.category)
            val status = CategoryStatus.of(score)
            row.status.text = status.label
            row.score.text = score?.toString() ?: getString(R.string.home_grade_placeholder)
            row.root.isSelected = row.category == chosen
            val colorRes = when (status) {
                CategoryStatus.OK -> ai.pleos.ds.R.color.pleos_informative_positive
                CategoryStatus.CAUTION -> ai.pleos.ds.R.color.pleos_regulation_yellow
                CategoryStatus.DANGER -> ai.pleos.ds.R.color.pleos_informative_negative
                CategoryStatus.UNMEASURED -> ai.pleos.ds.R.color.pleos_text_dark_tertiary
            }
            row.dot.backgroundTintList = ColorStateList.valueOf(requireContext().getColor(colorRes))
        }
    }

    private fun renderDetail(result: InspectionResult) {
        val category = selected ?: Category.POWER
        val score = result.evaluation?.categories?.get(category)
        val status = CategoryStatus.of(score)
        detailTitle.text = category.label
        detailStatus.text = if (score == null) {
            status.label
        } else {
            getString(R.string.status_detail_score, status.label, score)
        }
        val colorRes = when (status) {
            CategoryStatus.OK -> ai.pleos.ds.R.color.pleos_informative_positive
            CategoryStatus.CAUTION -> ai.pleos.ds.R.color.pleos_regulation_yellow
            CategoryStatus.DANGER -> ai.pleos.ds.R.color.pleos_informative_negative
            CategoryStatus.UNMEASURED -> ai.pleos.ds.R.color.pleos_text_dark_tertiary
        }
        detailStatus.setTextColor(requireContext().getColor(colorRes))
        detailValues.text = valueLines(category, result.readings, result.engine)
        detailDeductions.text = deductionLines(category, result.engine)

        val problem = biggest(category, result.engine)
        if (problem == null) {
            detailAdvice.text = ""
            detailAdvice.visibility = View.GONE
            detailAdviceLabel.visibility = View.GONE
            detailAction.visibility = View.GONE
            return
        }
        detailAdviceLabel.visibility = View.VISIBLE
        detailAdvice.visibility = View.VISIBLE
        detailAdvice.text = Priority.advice(problem, result.engine)
        val action = Priority.actionFor(problem, result.engine)
        detailAction.visibility = View.VISIBLE
        detailAction.text = action.label
        detailAction.setOnClickListener {
            val host = activity as? MainActivity ?: return@setOnClickListener
            FixLinks.start(host, action, host.isDrivingLimited()) { linkResult ->
                PleosToast.show(host, linkResult.message)
            }
        }
    }

    private fun renderTrend(result: InspectionResult) {
        val app = requireActivity().application as MocaApp
        val today = ScoreBridge.today()
        val points = if (result.demo) {
            val records = runCatching { Demo.recordsOf(app.prefs.demoScenarioId, today) }.getOrNull()
            if (records == null) {
                emptyList()
            } else {
                InspectionHistory.trendPoints(records, Profile.DEMO, today)
            }
        } else {
            InspectionHistory.trendPoints(app.prefs.inspectionLog(false), Profile.REAL, today)
        }
        if (points.size < 2) {
            trendGraph.visibility = View.GONE
            trendEmpty.visibility = View.VISIBLE
            return
        }
        trendEmpty.visibility = View.GONE
        trendGraph.visibility = View.VISIBLE
        trendGraph.points = points
    }

    private fun showUnread() {
        detailStatus.text = getString(R.string.c06_unmeasurable)
        detailValues.text = getString(R.string.c06_unmeasurable)
        detailDeductions.text = getString(R.string.status_no_deduction)
        detailAdvice.visibility = View.GONE
        detailAdviceLabel.visibility = View.GONE
        detailAction.visibility = View.GONE
        trendGraph.visibility = View.GONE
        trendEmpty.visibility = View.VISIBLE
    }

    /**
     * 변속기 오일·연료·에어백 전개는 앱이 읽지 않는다(ScoreBridge).
     * 엔진 기본값(정상)을 화면에 올리지 않고 측정 불가로 둔다.
     */
    private fun valueLines(
        category: Category,
        readings: Map<String, ItemReading>,
        engine: VehicleSnapshot,
    ): String {
        val lines = mutableListOf<String>()
        when (category) {
            Category.POWER -> {
                lines += fluidLine(
                    getString(R.string.status_item_engine_oil),
                    readings[PleosVehicleSource.KEY_OIL_ENGINE],
                    engine.engineOil,
                    Item.ENGINE_OIL,
                    engine,
                )
                lines += unread(getString(R.string.status_item_transmission_oil))
            }
            Category.BRAKE_STEERING -> {
                lines += fluidLine(
                    getString(R.string.status_item_brake_fluid),
                    readings[PleosVehicleSource.KEY_BRAKE_FLUID],
                    engine.brakeFluid,
                    Item.BRAKE_FLUID,
                    engine,
                )
            }
            Category.TIRE -> lines += tireLine(readings[PleosVehicleSource.KEY_TIRE_LOW], engine)
            Category.ELECTRIC -> {
                lines += batteryLine(readings[PleosVehicleSource.KEY_SOC])
                lines += unread(getString(R.string.status_item_fuel))
            }
            Category.SAFETY -> {
                lines += washerLine(readings[PleosVehicleSource.KEY_WASHER], engine)
                lines += unread(getString(R.string.status_item_airbag))
            }
        }
        lines += telltaleLines(category, readings[PleosVehicleSource.KEY_TELLTALES], engine)
        return lines.joinToString("\n")
    }

    private fun fluidLine(
        name: String,
        reading: ItemReading?,
        level: FluidLevel,
        item: Item,
        engine: VehicleSnapshot,
    ): String {
        if (reading != null && !reading.supported) return line(name, getString(R.string.c05_unsupported))
        if (reading == null || reading.value == null || level == FluidLevel.ERROR || item in engine.unsupported) {
            return unread(name)
        }
        val word = when (level) {
            FluidLevel.NORMAL -> getString(R.string.status_level_normal)
            FluidLevel.LOW -> getString(R.string.status_level_low)
            FluidLevel.CRITICALLY_LOW -> getString(R.string.status_level_critical)
            FluidLevel.HIGH -> getString(R.string.status_level_high)
            FluidLevel.ERROR -> getString(R.string.c06_unmeasurable)
        }
        return line(name, word)
    }

    private fun tireLine(reading: ItemReading?, engine: VehicleSnapshot): String {
        val name = getString(R.string.status_item_tire)
        if (reading != null && !reading.supported) return line(name, getString(R.string.c05_unsupported))
        if (reading == null || reading.value == null || Item.TIRE in engine.unsupported) return unread(name)
        val wheels = engine.lowTireWheels
        val word = if (wheels.isEmpty()) {
            val count = reading.value?.toInt() ?: 0
            if (count == 0) {
                getString(R.string.status_level_normal)
            } else {
                getString(R.string.status_tire_low_count, count)
            }
        } else {
            val where = WheelArea.entries.filter { it in wheels }.joinToString(", ") { it.label }
            getString(R.string.status_tire_low_where, where)
        }
        return line(name, word)
    }

    private fun batteryLine(reading: ItemReading?): String {
        val name = getString(R.string.status_item_battery)
        if (reading != null && !reading.supported) return line(name, getString(R.string.c05_unsupported))
        val value = reading?.value ?: return unread(name)
        val asLong = value.toLong()
        val shown = if (value == asLong.toDouble()) asLong.toString() else value.toString()
        return line(name, getString(R.string.status_percent_value, shown))
    }

    private fun washerLine(reading: ItemReading?, engine: VehicleSnapshot): String {
        val name = getString(R.string.status_item_washer)
        if (reading != null && !reading.supported) return line(name, getString(R.string.c05_unsupported))
        if (reading == null || reading.value == null || Item.WASHER_FLUID in engine.unsupported) return unread(name)
        val word = if (engine.washerFluidLow) {
            getString(R.string.status_level_low)
        } else {
            getString(R.string.status_level_normal)
        }
        return line(name, word)
    }

    private fun telltaleLines(
        category: Category,
        reading: ItemReading?,
        engine: VehicleSnapshot,
    ): List<String> {
        val name = getString(R.string.status_item_telltales)
        val relevant = Telltale.entries.any { Rules.categoryOf(it) == category }
        if (!relevant) return emptyList()
        if (reading != null && !reading.supported) return listOf(line(name, getString(R.string.c05_unsupported)))
        if (reading == null || reading.value == null || Item.TELLTALES in engine.unsupported) return listOf(unread(name))
        val active = engine.telltales.filter { state ->
            state.active && Rules.categoryOf(state.telltale) == category
        }
        if (active.isEmpty()) return listOf(line(name, getString(R.string.status_telltale_off)))
        return active.map { state ->
            val color = when (state.color) {
                Color.RED -> getString(R.string.status_color_red)
                Color.YELLOW -> getString(R.string.status_color_yellow)
                Color.GREEN -> getString(R.string.status_color_green)
                Color.OFF -> getString(R.string.status_telltale_off)
                Color.UNKNOWN -> getString(R.string.c06_unmeasurable)
            }
            getString(R.string.status_telltale_line, state.telltale.label, color)
        }
    }

    private fun deductionLines(category: Category, engine: VehicleSnapshot): String {
        val rows = Rules.deductions(engine).filter { it.category == category }
        if (rows.isEmpty()) return getString(R.string.status_no_deduction)
        return rows.joinToString("\n") { deduction ->
            getString(R.string.status_deduction_line, deduction.label, deduction.points)
        }
    }

    private fun biggest(category: Category, engine: VehicleSnapshot): Deduction? =
        Rules.deductions(engine)
            .filter { it.category == category }
            .minWithOrNull(compareByDescending<Deduction> { it.points }.thenBy { it.group })

    private fun unread(name: String): String = line(name, getString(R.string.c06_unmeasurable))

    private fun line(name: String, value: String): String = getString(R.string.status_value_line, name, value)

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

    companion object {
        const val ARG_CATEGORY = "category"
    }
}
