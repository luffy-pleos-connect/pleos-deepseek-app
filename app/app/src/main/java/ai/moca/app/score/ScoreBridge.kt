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
        fun missingOrUnsupported(key: String): Boolean = readings[key]?.supported != true
        val unsupported = buildSet {
            if (missingOrUnsupported(PleosVehicleSource.KEY_OIL_ENGINE)) add(Item.ENGINE_OIL)
            // 변속기 오일은 이번 단계에서 읽지 않는다 → 측정 불가.
            add(Item.TRANSMISSION_OIL)
            if (missingOrUnsupported(PleosVehicleSource.KEY_BRAKE_FLUID)) add(Item.BRAKE_FLUID)
            // 워셔액·에어백은 이번 단계에서 읽지 않는다 → 측정 불가.
            add(Item.WASHER_FLUID)
            if (missingOrUnsupported(PleosVehicleSource.KEY_TIRE_LOW)) add(Item.TIRE)
            if (missingOrUnsupported(PleosVehicleSource.KEY_TELLTALES)) add(Item.TELLTALES)
            if (readings[PleosVehicleSource.KEY_SOC]?.value == null) add(Item.ENERGY)
            // 에어백 전개 여부는 이번 단계에서 읽지 않는다 → 측정 불가로 넘긴다.
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
     * ⚠️ 색 정수 매핑이 미확인이라(G0 보고서 §4) 실제 type 정보는 쓰지 못하고
     * 개수만 RED/YELLOW 로 나눈다. 실값을 얻으면 PleosVehicleSource 의 리스너에서
     * type·color 를 그대로 넘기도록 고친다.
     */
    private fun telltalesFrom(warnings: WarningSummary): List<TelltaleState> = buildList {
        repeat(warnings.redCount) { add(TelltaleState(Telltale.MASTER_WARNING, Color.RED)) }
        repeat(warnings.yellowCount) { add(TelltaleState(Telltale.MASTER_WARNING, Color.YELLOW)) }
    }

    /** 관리 점수는 이번 단계에서 저장 기록이 없으므로 시즌 시작값 50 을 쓴다(엔진 규칙). */
    fun careForNow(): Int = 50

    fun evaluate(
        readings: Map<String, ItemReading>,
        warnings: WarningSummary,
        isDemo: Boolean,
    ): Evaluation {
        val snapshot = toEngineSnapshot(readings, warnings, isDemo)
        val redCap = Rules.hasRed(snapshot)
        return Scoring.evaluate(snapshot, careForNow(), redCap)
    }

    fun today(): LocalDate = LocalDate.now(SEOUL)
}
