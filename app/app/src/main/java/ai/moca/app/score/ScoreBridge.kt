package ai.moca.app.score

import ai.moca.app.vehicle.ActiveWarning
import ai.moca.app.vehicle.ItemReading
import ai.moca.app.vehicle.PleosVehicleSource
import ai.moca.app.vehicle.TelltaleColorMap
import ai.moca.app.vehicle.TelltaleTypeMap
import ai.moca.app.vehicle.WarningCounter
import ai.moca.app.vehicle.WarningSummary
import moca.core.Color
import moca.core.Demo
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
 * 앱의 원본값 읽기([ItemReading])와 점수 엔진의 입력([VehicleSnapshot])을 서로 바꾼다.
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
     * 엔진 스냅샷 → 읽기 값. 화면이 읽은 값으로 그리는 동안 데모에도 같은 모양을 쓴다.
     * [toEngineSnapshot] 에 그대로 넣으면 처음 스냅샷이 나온다.
     */
    fun toReadings(snapshot: VehicleSnapshot): Map<String, ItemReading> {
        val map = LinkedHashMap<String, ItemReading>()
        fun put(key: String, value: Double?, supported: Boolean = true, note: String? = null) {
            map[key] = ItemReading(key, supported, value, note)
        }

        put(
            PleosVehicleSource.KEY_OIL_ENGINE,
            oilValue(snapshot.engineOil),
            supported = Item.ENGINE_OIL !in snapshot.unsupported,
            note = if (snapshot.engineOil == FluidLevel.ERROR) NOTE_SENSOR_ERROR else null,
        )
        put(
            PleosVehicleSource.KEY_OIL_TRANSMISSION,
            oilValue(snapshot.transmissionOil),
            supported = Item.TRANSMISSION_OIL !in snapshot.unsupported,
            note = if (snapshot.transmissionOil == FluidLevel.ERROR) NOTE_SENSOR_ERROR else null,
        )
        put(
            PleosVehicleSource.KEY_BRAKE_FLUID,
            oilValue(snapshot.brakeFluid),
            supported = Item.BRAKE_FLUID !in snapshot.unsupported,
            note = if (snapshot.brakeFluid == FluidLevel.ERROR) NOTE_SENSOR_ERROR else null,
        )
        put(
            PleosVehicleSource.KEY_TIRE_LOW,
            if (Item.TIRE in snapshot.unsupported) null else snapshot.lowTireWheels.size.toDouble(),
            supported = Item.TIRE !in snapshot.unsupported,
            note = tireNote(snapshot.lowTireWheels),
        )
        put(
            PleosVehicleSource.KEY_SOC,
            snapshot.batteryPercent,
            supported = snapshot.batteryPercent != null && Item.ENERGY !in snapshot.unsupported,
        )
        put(
            PleosVehicleSource.KEY_FUEL,
            snapshot.fuelPercent,
            supported = snapshot.fuelPercent != null && Item.ENERGY !in snapshot.unsupported,
        )
        put(
            PleosVehicleSource.KEY_WASHER,
            if (snapshot.washerFluidLow) 1.0 else 0.0,
            supported = Item.WASHER_FLUID !in snapshot.unsupported,
        )
        put(
            PleosVehicleSource.KEY_AIRBAG,
            if (snapshot.airbagDeployed) 1.0 else 0.0,
            supported = Item.AIRBAG !in snapshot.unsupported,
        )
        put(
            PleosVehicleSource.KEY_TELLTALES,
            snapshot.telltales.count { it.active }.toDouble(),
            supported = Item.TELLTALES !in snapshot.unsupported,
        )
        // 경고등이 이미 담고 있으므로 파워다운은 따로 켜지 않는다(두 번 넣지 않는다).
        put(PleosVehicleSource.KEY_POWER_DOWN, 0.0)
        return map
    }

    /** 엔진 스냅샷 → 화면 배지용 경고 요약. 감점 대상만 세는 규칙은 [WarningCounter] 에 있다. */
    fun toWarnings(snapshot: VehicleSnapshot, isDemo: Boolean): WarningSummary =
        WarningCounter.summarize(
            snapshot.telltales.map { state ->
                ActiveWarning(
                    type = TelltaleTypeMap.toSdk(state.telltale),
                    color = TelltaleColorMap.assumedInt(state.color),
                    blinking = state.blinking,
                )
            },
            isDemo = isDemo,
        )

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
        reading.note == NOTE_SENSOR_ERROR -> FluidLevel.ERROR
        reading.value == null -> FluidLevel.NORMAL
        reading.value >= 3.0 -> FluidLevel.HIGH
        reading.value >= 2.0 -> FluidLevel.CRITICALLY_LOW
        reading.value >= 1.0 -> FluidLevel.LOW
        else -> FluidLevel.NORMAL
    }

    /** [fluid] 의 반대. ERROR 는 note 로만 표현할 수 있다. */
    private fun oilValue(level: FluidLevel): Double = when (level) {
        FluidLevel.LOW -> 1.0
        FluidLevel.CRITICALLY_LOW -> 2.0
        FluidLevel.HIGH -> 3.0
        FluidLevel.NORMAL, FluidLevel.ERROR -> 0.0
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

    private fun tireNote(wheels: Set<WheelArea>): String? {
        val names = WheelArea.entries.filter { it in wheels }.map { it.name }
        return if (names.isEmpty()) null else names.joinToString("+")
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
     * 임시값: 4=RED, 3=YELLOW (TelltaleColor ordinal 가정, [TelltaleColorMap] 과 같은 숫자).
     */
    private fun Int.toEngineColor(): Color = when (this) {
        4 -> Color.RED
        3 -> Color.YELLOW
        else -> Color.UNKNOWN
    }

    /**
     * 점수 평가. 관리 점수는 저장된 점검 기록에서 계산해 넘긴다.
     * 넘기지 않으면 데모는 엔진 데모 값(53), 실제는 이번 달 시작값(50)이다.
     */
    fun evaluate(
        readings: Map<String, ItemReading>,
        warnings: WarningSummary,
        isDemo: Boolean,
        care: Int = if (isDemo) Demo.CARE_TODAY else 50,
    ): Evaluation {
        val snapshot = toEngineSnapshot(readings, warnings, isDemo)
        return Scoring.evaluate(snapshot, care, Rules.hasRed(snapshot))
    }

    fun today(): LocalDate = LocalDate.now(SEOUL)

    private const val NOTE_SENSOR_ERROR = "sensor_error"
}
