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
        val unsupported = buildSet {
            if (readings[PleosVehicleSource.KEY_OIL_ENGINE]?.supported == false) add(Item.ENGINE_OIL)
            if (readings[PleosVehicleSource.KEY_BRAKE_FLUID]?.supported == false) add(Item.BRAKE_FLUID)
            if (readings[PleosVehicleSource.KEY_WASHER]?.supported == false) add(Item.WASHER_FLUID)
            if (readings[PleosVehicleSource.KEY_TIRE_LOW]?.supported == false) add(Item.TIRE)
            if (readings[PleosVehicleSource.KEY_TELLTALES]?.supported == false) add(Item.TELLTALES)
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
