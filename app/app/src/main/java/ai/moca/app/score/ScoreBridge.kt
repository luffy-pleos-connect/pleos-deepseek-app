package ai.moca.app.score

import ai.moca.app.data.Prefs
import ai.moca.app.vehicle.ItemReading
import ai.moca.app.vehicle.PleosVehicleSource
import ai.moca.app.vehicle.TelltaleTypeMap
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
            if (missingOrUnsupported(PleosVehicleSource.KEY_OIL_TRANSMISSION)) add(Item.TRANSMISSION_OIL)
            if (missingOrUnsupported(PleosVehicleSource.KEY_BRAKE_FLUID)) add(Item.BRAKE_FLUID)
            if (missingOrUnsupported(PleosVehicleSource.KEY_WASHER)) add(Item.WASHER_FLUID)
            if (missingOrUnsupported(PleosVehicleSource.KEY_TIRE_LOW)) add(Item.TIRE)
            if (missingOrUnsupported(PleosVehicleSource.KEY_TELLTALES)) add(Item.TELLTALES)
            // 연료와 배터리는 같은 구역(전기)의 잔량이다. 둘 다 못 읽을 때만 측정 불가.
            if (missingOrUnsupported(PleosVehicleSource.KEY_SOC) &&
                missingOrUnsupported(PleosVehicleSource.KEY_FUEL)
            ) {
                add(Item.ENERGY)
            }
            if (missingOrUnsupported(PleosVehicleSource.KEY_AIRBAG)) add(Item.AIRBAG)
        }

        val telltales = telltalesFrom(warnings).toMutableList()
        // 엔진에는 "파워다운 경고" 입력 칸이 없다. 켜져 있으면 파워다운 노랑 경고등으로 넘긴다
        // (최종정리 2.3절 −25). 경고등 목록에 이미 있으면 두 번 넣지 않는다.
        val powerDown = (readings[PleosVehicleSource.KEY_POWER_DOWN]?.value ?: 0.0) > 0.0
        if (powerDown && telltales.none { it.telltale == Telltale.POWER_DOWN }) {
            telltales += TelltaleState(Telltale.POWER_DOWN, Color.YELLOW)
        }

        return VehicleSnapshot(
            engineOil = fluid(readings[PleosVehicleSource.KEY_OIL_ENGINE]),
            transmissionOil = fluid(readings[PleosVehicleSource.KEY_OIL_TRANSMISSION]),
            brakeFluid = fluid(readings[PleosVehicleSource.KEY_BRAKE_FLUID]),
            lowTireWheels = tireWheels(readings[PleosVehicleSource.KEY_TIRE_LOW]),
            batteryPercent = readings[PleosVehicleSource.KEY_SOC]?.value,
            // 못 읽으면 null. core 의 기본값(62.0)이 실제 기록에 남지 않게 한다.
            fuelPercent = readings[PleosVehicleSource.KEY_FUEL]?.value,
            washerFluidLow = (readings[PleosVehicleSource.KEY_WASHER]?.value ?: 0.0) > 0.0,
            airbagDeployed = (readings[PleosVehicleSource.KEY_AIRBAG]?.value ?: 0.0) > 0.0,
            telltales = telltales,
            unsupported = unsupported,
        )
    }

    /**
     * fluid 는 감점 판정용이다. "못 읽음"은 여기서 표현하지 않는다.
     * 못 읽음(value=null·미지원)은 [toEngineSnapshot] 의 unsupported 집합이 담당한다.
     * 여기서 ERROR 로 만들면 Rules.measured 가 카테고리를 통째로 지워
     * 경고등이 있는데도 동력/안전이 측정 불가가 되는 부작용이 있다(2026-11-11 확인).
     *
     * 값의 약속(PleosVehicleSource 의 [oilReading] 과 같다): 0 = 정상, 1 = 부족,
     * 2 = 심각 부족, 3 = 과다. 네 값을 순서대로 나눈다 — 3(과다)을 2 이상으로 묶으면 안 된다.
     */
    private fun fluid(reading: ItemReading?): FluidLevel = when {
        reading == null || !reading.supported -> FluidLevel.NORMAL
        reading.note == "sensor_error" -> FluidLevel.ERROR
        reading.value == null -> FluidLevel.NORMAL
        reading.value >= 3.0 -> FluidLevel.HIGH
        reading.value >= 2.0 -> FluidLevel.CRITICALLY_LOW
        reading.value >= 1.0 -> FluidLevel.LOW
        else -> FluidLevel.NORMAL
    }

    /**
     * 저압 바퀴는 읽기 결과의 위치(`note`, 예: `FL+RR`)를 쓴다.
     * 개수만 있고 위치가 없으면 앞에서부터 바퀴를 만들지 않는다.
     */
    private fun tireWheels(reading: ItemReading?): Set<WheelArea> {
        if (reading == null || !reading.supported || reading.value == null) return emptySet()
        val note = reading.note ?: return emptySet()
        return note.split('+')
            .mapNotNull { token ->
                val name = token.trim()
                WheelArea.entries.firstOrNull { it.name == name }
            }
            .toSet()
    }

    /**
     * 경고등 요약 → 엔진 입력.
     * 색 정수 ↔ RED/YELLOW 매핑은 아직 미확인이지만(G0 §4), **type 은 실제 값을 쓴다.**
     * type 을 MASTER_WARNING 으로 뭉개면 ABS 빨강이 안전 카테고리로 가서
     * 제동 점수가 틀린다(감사 2026-11-11). 종류 대응표는 [TelltaleTypeMap] 한 곳에만 있다.
     */
    private fun telltalesFrom(warnings: WarningSummary): List<TelltaleState> =
        warnings.active.mapNotNull { active ->
            val type = TelltaleTypeMap.toEngine(active.type) ?: return@mapNotNull null
            TelltaleState(type, active.color.toEngineColor(), active.blinking)
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
