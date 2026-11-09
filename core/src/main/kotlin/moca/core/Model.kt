package moca.core

import java.time.LocalDate

enum class Category(val label: String) {
    POWER("동력"),
    BRAKE_STEERING("제동·조향"),
    TIRE("타이어"),
    ELECTRIC("전기"),
    SAFETY("안전"),
}

enum class WheelArea(val label: String) {
    FL("앞왼쪽"),
    FR("앞오른쪽"),
    RL("뒤왼쪽"),
    RR("뒤오른쪽"),
}

enum class FluidLevel { NORMAL, LOW, CRITICALLY_LOW, HIGH, ERROR }

enum class Color { RED, YELLOW, GREEN, OFF, UNKNOWN }

enum class Telltale(val label: String) {
    ENGINE("엔진"),
    ENGINE_OIL_PRESSURE("엔진오일 압력"),
    ENGINE_TEMPERATURE("엔진 온도"),
    EV_SYSTEM("EV 시스템"),
    HEV_SYSTEM("HEV 시스템"),
    PARTICLE_FILTER("매연 필터"),
    ABS("ABS"),
    ESC("ESC"),
    MDPS("MDPS"),
    TPMS("타이어 공기압"),
    LOW_VOLT_BATTERY("12V 배터리"),
    POWER_DOWN("파워다운"),
    AIRBAG("에어백"),
    MASTER_WARNING("마스터 경고"),
    LOW_FUEL("연료 부족"),
    LOW_EV_BATTERY("EV 배터리 부족"),
    SEATBELT("안전벨트"),
    PASSENGER_AIRBAG_OFF("동승석 에어백 꺼짐"),
}

data class TelltaleState(
    val telltale: Telltale,
    val color: Color,
    val blinking: Boolean = false,
) {
    val active: Boolean get() = color == Color.RED || color == Color.YELLOW
}

enum class Item {
    ENGINE_OIL,
    TRANSMISSION_OIL,
    BRAKE_FLUID,
    TIRE,
    ENERGY,
    WASHER_FLUID,
    AIRBAG,
    TELLTALES,
}

data class VehicleSnapshot(
    val engineOil: FluidLevel = FluidLevel.NORMAL,
    val transmissionOil: FluidLevel = FluidLevel.NORMAL,
    val brakeFluid: FluidLevel = FluidLevel.NORMAL,
    val lowTireWheels: Set<WheelArea> = emptySet(),
    val fuelPercent: Double? = 62.0,
    val batteryPercent: Double? = 80.0,
    val washerFluidLow: Boolean = false,
    val airbagDeployed: Boolean = false,
    val telltales: List<TelltaleState> = emptyList(),
    val unsupported: Set<Item> = emptySet(),
) {
    companion object {
        fun normal(): VehicleSnapshot = VehicleSnapshot()
    }
}

enum class Profile { REAL, DEMO }

data class Inspection(
    val profile: Profile,
    val inspectedAt: LocalDate,
    val snapshot: VehicleSnapshot,
    val seq: Long = 0L,
    val scenarioId: String? = null,
)

enum class Grade(val label: String) {
    D("D"),
    C("C"),
    B("B"),
    A("A"),
    S("S"),
    S_PLUS("S+");

    val threshold: Int
        get() = when (this) {
            D -> 0
            C -> 60
            B -> 70
            A -> 80
            S -> 90
            S_PLUS -> 95
        }

    val healthThreshold: Double
        get() = when (this) {
            S_PLUS -> 95.0
            S -> 90.0
            else -> 0.0
        }

    fun next(): Grade? = entries.getOrNull(ordinal + 1)

    companion object {
        fun worst(a: Grade, b: Grade): Grade = if (a.ordinal <= b.ordinal) a else b
    }
}

data class Evaluation(
    val categories: Map<Category, Int?>,
    val health: Double?,
    val care: Int?,
    val overall: Double?,
    val grade: Grade?,
    val redCap: Boolean,
    val displayedOverall: Int?,
    val nextGrade: Grade?,
    val pointsToNext: Int?,
    val healthCapped: Boolean,
    val hasRed: Boolean = false,
    val redCapNotice: String?,
    val healthNotice: String?,
)

data class GradeDecision(
    val grade: Grade,
    val redCapped: Boolean,
    val healthCapped: Boolean,
)

data class CareResult(
    val score: Int,
    val seasonLabel: String?,
    val previousSeasonGrade: Grade?,
)

data class BattleRound(
    val category: Category,
    val mine: Int?,
    val theirs: Int?,
    val winner: BattleSide,
)

enum class BattleSide { MINE, THEIRS, DRAW }

data class BattleResult(
    val rounds: List<BattleRound>,
    val myWins: Int,
    val theirWins: Int,
    val winner: BattleSide,
    val tieBreakUsed: Boolean,
    val myOverall: Double?,
    val theirOverall: Double?,
)
