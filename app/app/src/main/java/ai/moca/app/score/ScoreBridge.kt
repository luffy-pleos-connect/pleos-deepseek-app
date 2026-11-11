package ai.moca.app.score

import ai.moca.app.data.Prefs
import ai.moca.app.vehicle.ItemReading
import ai.moca.app.vehicle.PleosVehicleSource
import ai.moca.app.vehicle.WarningSummary
import moca.core.Category
import moca.core.Color
import moca.core.Evaluation
import moca.core.FluidLevel
import moca.core.Item
import moca.core.Rules
import moca.core.Scoring
import moca.core.Telltale
import moca.core.TelltaleState
import moca.core.VehicleSnapshot
import moca.core.WheelArea
import java.time.LocalDate
import java.time.ZoneId

/**
 * 앱의 원본값 읽기([ItemReading])를 점수 엔진의 입력([VehicleSnapshot])으로 바꾼다.
 * 엔진은 :core 의 순수 함수다. 여기서는 변환만 한다.
 */
object ScoreBridge {

    private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

    fun toEngineSnapshot(
        readings: Map<String, ItemReading>,
        warnings: WarningSummary,
        isDemo: Boolean,
    ): VehicleSnapshot {
        // 항목이 아예 없으면(읽지 못함) "정상"이 아니라 "측정 불가"다.
        // 이렇게 하지 않으면 엔진이 기본값(정상 차)으로 평가해 없는 등급을 만들어 낸다.
        // value=null 은 "지원하지만 이번에 못 읽음"이다. 정상으로 계산되면 안 되므로
        // unsupported 에 넣어 카테고리를 측정 불가로 만든다.
        fun missingOrUnsupported(key: String): Boolean {
            val r = readings[key] ?: return true
            return !r.supported || r.value == null
        }
        // 규칙: "이번에 값을 못 읽었으면 미지원(=측정 불가)".
        // 단, 실제 값이 있으면 지원으로 인정한다. 읽지 않는 항목을 무조건
        // 미지원으로 넣으면 경고등이 있는데도 그 카테고리가 통째로 사라진다.
        val unsupported = buildSet {
            if (missingOrUnsupported(PleosVehicleSource.KEY_OIL_ENGINE)) add(Item.ENGINE_OIL)
            // 변속기 오일은 이번 단계에서 읽지 않는다 → 측정 불가.
            add(Item.TRANSMISSION_OIL)
            if (missingOrUnsupported(PleosVehicleSource.KEY_BRAKE_FLUID)) add(Item.BRAKE_FLUID)
            if (missingOrUnsupported(PleosVehicleSource.KEY_WASHER)) add(Item.WASHER_FLUID)
            if (missingOrUnsupported(PleosVehicleSource.KEY_TIRE_LOW)) add(Item.TIRE)
            if (missingOrUnsupported(PleosVehicleSource.KEY_TELLTALES)) add(Item.TELLTALES)
            if (readings[PleosVehicleSource.KEY_SOC]?.value == null) add(Item.ENERGY)
            // 에어백 전개 여부는 이번 단계에서 읽지 않는다 → 측정 불가.
            add(Item.AIRBAG)
        }

        return VehicleSnapshot(
            engineOil = fluid(readings[PleosVehicleSource.KEY_OIL_ENGINE]),
            brakeFluid = fluid(readings[PleosVehicleSource.KEY_BRAKE_FLUID]),
            lowTireWheels = tireWheels(readings[PleosVehicleSource.KEY_TIRE_LOW]),
            batteryPercent = readings[PleosVehicleSource.KEY_SOC]?.value,
            washerFluidLow = (readings[PleosVehicleSource.KEY_WASHER]?.value ?: 0.0) > 0.0,
            telltales = telltalesFrom(warnings),
            unsupported = unsupported,
        )
    }

    /**
     * fluid 는 감점 판정용이다. "못 읽음"은 여기서 표현하지 않는다.
     * 못 읽음(value=null·미지원)은 [toEngineSnapshot] 의 unsupported 집합이 담당한다.
     * 여기서 ERROR 로 만들면 Rules.measured 가 카테고리를 통째로 지워
     * 경고등이 있는데도 동력/안전이 측정 불가가 되는 부작용이 있다(2026-11-11 확인).
     */
    private fun fluid(reading: ItemReading?): FluidLevel = when {
        reading == null || !reading.supported -> FluidLevel.NORMAL
        reading.note == "sensor_error" -> FluidLevel.ERROR
        reading.value == null -> FluidLevel.NORMAL
        reading.value >= 2.0 -> FluidLevel.CRITICALLY_LOW
        reading.value >= 1.5 -> FluidLevel.HIGH
        reading.value >= 0.5 -> FluidLevel.LOW
        else -> FluidLevel.NORMAL
    }

    private fun tireWheels(reading: ItemReading?): Set<WheelArea> {
        val count = reading?.value?.toInt() ?: return emptySet()
        return WheelArea.entries.take(count.coerceIn(0, 4)).toSet()
    }

    /**
     * 경고등 요약 → 엔진 입력.
     * 색 정수 ↔ RED/YELLOW 매핑은 아직 미확인이지만(G0 §4), **type 은 실제 값을 쓴다.**
     * type 을 MASTER_WARNING 으로 뭉개면 ABS 빨강이 안전 카테고리로 가서
     * 제동 점수가 틀린다(감사 2026-11-11).
     */
    private fun telltalesFrom(warnings: WarningSummary): List<TelltaleState> =
        warnings.active.mapNotNull { active ->
            val type = mapTelltale(active.type) ?: return@mapNotNull null
            TelltaleState(type, active.color.toEngineColor())
        }

    /**
     * 앱의 경고등 type 문자열 → :core Telltale.
     * Pleos `TelltaleType.name` 과 :core enum 이름이 같은 것을 확인해 매핑한다(2026-11-11).
     * 매핑에 없는 type 은 MASTER_WARNING 으로 두지 않고 **null 로 제외**한다 —
     * 엉뚱한 카테고리에 감점을 넣으면 점수가 틀린다.
     */
    private fun mapTelltale(type: String): Telltale? = when (type.uppercase()) {
        "ABS" -> Telltale.ABS
        "ESC" -> Telltale.ESC
        "MDPS" -> Telltale.MDPS
        "TPMS" -> Telltale.TPMS
        "AIRBAG" -> Telltale.AIRBAG
        "MASTER_WARNING" -> Telltale.MASTER_WARNING
        "ENGINE" -> Telltale.ENGINE
        "ENGINE_OIL_PRESSURE" -> Telltale.ENGINE_OIL_PRESSURE
        "ENGINE_TEMPERATURE" -> Telltale.ENGINE_TEMPERATURE
        "EV_SYSTEM" -> Telltale.EV_SYSTEM
        "HEV_SYSTEM" -> Telltale.HEV_SYSTEM
        "PARTICLE_FILTER" -> Telltale.PARTICLE_FILTER
        "LOW_VOLT_BATTERY" -> Telltale.LOW_VOLT_BATTERY
        "POWER_DOWN" -> Telltale.POWER_DOWN
        "LOW_FUEL" -> Telltale.LOW_FUEL
        "LOW_EV_BATTERY" -> Telltale.LOW_EV_BATTERY
        "SEAT_BELT" -> Telltale.SEATBELT
        "PASSENGER_AIRBAG_OFF" -> Telltale.PASSENGER_AIRBAG_OFF
        else -> null
    }

    /**
     * 정수 색 → :core Color.
     * ⚠️ 정수↔색 대응은 미확인이다(G0 §4). 임시 매핑이며, 틀리면 모든 경고등 감점이 흔들린다.
     * 임시값: 4=RED, 3=YELLOW (TelltaleColor ordinal 가정).
     */
    private fun Int.toEngineColor(): Color = when (this) {
        4 -> Color.RED
        3 -> Color.YELLOW
        else -> Color.UNKNOWN
    }

    /**
     * 실차 관리 점수. 앱이 저장된 점검 기록으로 바꾼다.
     * 기록이 없으면 시즌 시작값 50.
     */
    var realCare: (LocalDate) -> Int = { _ -> 50 }

    /**
     * 관리 점수.
     * - 데모: `Demo.CARE_TODAY` (53). 기록으로 바꾸지 않는다.
     * - 실차: [realCare]
     */
    fun careForNow(isDemo: Boolean = false): Int =
        if (isDemo) moca.core.Demo.CARE_TODAY else realCare(today())

    fun evaluate(
        readings: Map<String, ItemReading>,
        warnings: WarningSummary,
        isDemo: Boolean,
    ): Evaluation {
        val snapshot = toEngineSnapshot(readings, warnings, isDemo)
        val care = careForNow(isDemo)
        val redCap = Rules.hasRed(snapshot)
        return Scoring.evaluate(snapshot, care, redCap)
    }

    fun today(): LocalDate = LocalDate.now(SEOUL)
}
