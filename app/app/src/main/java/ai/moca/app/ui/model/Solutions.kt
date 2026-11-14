package ai.moca.app.ui.model

import moca.core.Category
import moca.core.Color
import moca.core.Deduction
import moca.core.Evaluation
import moca.core.FluidLevel
import moca.core.Rules
import moca.core.VehicleSnapshot

/** 최종정리 5장 5번 표의 해결방안 세 종류. */
enum class SolutionKind { REPAIR_SHOP, BUY, SELF }

/** 감점 원인 줄에 붙는 단추. QR 은 단추가 아니라 그 줄 안의 그림이다. */
enum class SolutionButton { FIND_REPAIR, QR_INFLATOR, QR_WASHER, GAS_STATION, CHARGING_STATION }

/** 해결방안 안내 문구. strings.xml 의 solution_text_* 와 1:1 이다. */
enum class SolutionText {
    WARNING_RED,
    WARNING_YELLOW,
    ENGINE_OIL_LOW,
    ENGINE_OIL_HIGH,
    TRANSMISSION_OIL,
    BRAKE_FLUID,
    AIRBAG,
    TIRE_LOW,
    WASHER,
    FUEL_LOW,
    BATTERY_LOW,
    ENERGY_BOTH_LOW,
}

/** 감점 원인 하나와 그 해결방안. [points] 는 감점 점수(양수). */
data class Solution(
    val group: String,
    val label: String,
    val points: Int,
    val category: Category,
    val kinds: List<SolutionKind>,
    val text: SolutionText,
    val stopFirst: Boolean,
    val buttons: List<SolutionButton>,
)

/**
 * 감점 원인마다 어떤 해결방안·문구·단추가 붙는지 정한다(최종정리 5장 5번 표).
 * 순서와 점수는 엔진([Rules.deductions])이 정한 것을 그대로 쓴다. 화면은 그리기만 한다.
 */
object Solutions {

    private const val LOW_ENERGY_PERCENT = 10.0

    /** [Rules.deductions] 순서 그대로, 감점 원인마다 하나. */
    fun of(snapshot: VehicleSnapshot): List<Solution> =
        Rules.deductions(snapshot).map { solutionOf(it, snapshot) }

    /** 한 구역의 것만, 감점이 큰 것부터. */
    fun of(snapshot: VehicleSnapshot, category: Category): List<Solution> =
        Rules.deductions(snapshot)
            .filter { it.category == category }
            .map { solutionOf(it, snapshot) }
            .sortedWith(compareByDescending<Solution> { it.points }.thenBy { it.group })

    private fun solutionOf(deduction: Deduction, snapshot: VehicleSnapshot): Solution {
        val repairOnly = listOf(SolutionKind.REPAIR_SHOP)
        return when (deduction.group) {
            "ENERGY" -> energy(deduction, snapshot)
            "WASHER" -> Solution(
                deduction.group, deduction.label, deduction.points, deduction.category,
                kinds = listOf(SolutionKind.BUY),
                text = SolutionText.WASHER,
                stopFirst = false,
                buttons = listOf(SolutionButton.QR_WASHER),
            )
            "ENGINE_OIL" -> Solution(
                deduction.group, deduction.label, deduction.points, deduction.category,
                kinds = repairOnly,
                text = if (snapshot.engineOil == FluidLevel.HIGH) {
                    SolutionText.ENGINE_OIL_HIGH
                } else {
                    SolutionText.ENGINE_OIL_LOW
                },
                stopFirst = false,
                buttons = listOf(SolutionButton.FIND_REPAIR),
            )
            "TRANSMISSION_OIL" -> repairShop(deduction, SolutionText.TRANSMISSION_OIL)
            "BRAKE_FLUID" -> repairShop(deduction, SolutionText.BRAKE_FLUID)
            "AIRBAG" -> repairShop(
                deduction,
                SolutionText.AIRBAG,
                stopFirst = isRed(snapshot, deduction.group),
            )
            "TIRE_PRESSURE" -> if (snapshot.lowTireWheels.isEmpty()) {
                warning(deduction, snapshot)
            } else {
                Solution(
                    deduction.group, deduction.label, deduction.points, deduction.category,
                    kinds = listOf(SolutionKind.BUY, SolutionKind.REPAIR_SHOP),
                    text = SolutionText.TIRE_LOW,
                    stopFirst = false,
                    buttons = listOf(SolutionButton.QR_INFLATOR, SolutionButton.FIND_REPAIR),
                )
            }
            else -> warning(deduction, snapshot)
        }
    }

    private fun repairShop(
        deduction: Deduction,
        text: SolutionText,
        stopFirst: Boolean = false,
    ): Solution = Solution(
        deduction.group, deduction.label, deduction.points, deduction.category,
        kinds = listOf(SolutionKind.REPAIR_SHOP),
        text = text,
        stopFirst = stopFirst,
        buttons = listOf(SolutionButton.FIND_REPAIR),
    )

    /** 경고등 감점. 색은 점수가 아니라 스냅샷에 찍힌 색으로 가른다. */
    private fun warning(deduction: Deduction, snapshot: VehicleSnapshot): Solution {
        val red = isRed(snapshot, deduction.group)
        return Solution(
            deduction.group, deduction.label, deduction.points, deduction.category,
            kinds = listOf(SolutionKind.REPAIR_SHOP),
            text = if (red) SolutionText.WARNING_RED else SolutionText.WARNING_YELLOW,
            stopFirst = red,
            buttons = listOf(SolutionButton.FIND_REPAIR),
        )
    }

    private fun energy(deduction: Deduction, snapshot: VehicleSnapshot): Solution {
        val fuelLow = snapshot.fuelPercent?.let { it < LOW_ENERGY_PERCENT } == true
        val batteryLow = snapshot.batteryPercent?.let { it < LOW_ENERGY_PERCENT } == true
        val text = when {
            fuelLow && batteryLow -> SolutionText.ENERGY_BOTH_LOW
            batteryLow -> SolutionText.BATTERY_LOW
            else -> SolutionText.FUEL_LOW
        }
        val buttons = buildList {
            if (fuelLow) add(SolutionButton.GAS_STATION)
            if (batteryLow) add(SolutionButton.CHARGING_STATION)
        }
        return Solution(
            deduction.group, deduction.label, deduction.points, deduction.category,
            kinds = listOf(SolutionKind.SELF),
            text = text,
            stopFirst = false,
            buttons = buttons,
        )
    }

    private fun isRed(snapshot: VehicleSnapshot, group: String): Boolean {
        if (moca.core.Item.TELLTALES in snapshot.unsupported) return false
        return snapshot.telltales.any { state ->
            state.active && state.color == Color.RED && Rules.groupOf(state.telltale) == group
        }
    }
}

/**
 * 구역 카드 순서(최종정리 5장 2번). 점수가 낮은 구역이 맨 위, 같으면 구역 순서, 측정 불가는 맨 뒤.
 */
object StatusOrder {

    fun of(evaluation: Evaluation): List<Category> = Category.entries.sortedWith(
        compareBy<Category> { evaluation.categories[it] == null }
            .thenBy { evaluation.categories[it] ?: 0 }
            .thenBy { it.ordinal },
    )
}
