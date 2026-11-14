package ai.moca.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.telltale.TelltaleData
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Pleos Vehicle SDK 구현. 읽기만 한다(CONTROL_ 권한 없음).
 *
 * 확인된 제약 (docs/evidence/G0/report.md):
 * - 오일·브레이크액은 Pleos 에뮬레이터 VHAL에 속성이 없어 읽기가 예외로 실패한다 → 미지원으로 처리.
 * - 경고등 색은 Int 로 오지만 Int ↔ RED/YELLOW 대응은 미확인. [TelltaleColorMap] 한 곳에 격리했다.
 */
class PleosVehicleSource(context: Context) : VehicleSource {

    private val appContext = context.applicationContext
    private val vehicle = Vehicle(appContext)

    @Volatile
    private var warningCache = WarningSummary.empty()

    private var listener: TelltaleInfoListener? = null

    /** 처음 한 번만 남기는 로그 이름. */
    private val logged = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    // Pleos SDK 리스너 콜백은 백그라운드 스레드에서 온다.
    // 캐시 갱신은 volatile 대입만 하고, 화면 갱신은 메인스레드로 넘긴다(CalledFromWrongThreadException 방지).
    private val telltaleListener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(telltales: Telltales?) {
            warningCache = summarize(telltales?.telltales.orEmpty(), isDemo = false)
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "telltale listener failed: ${e.javaClass.simpleName}")
        }
    }

    private var initialized = false

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            runCatching { vehicle.initialize() }
                .onFailure { Log.e(TAG, "initialize failed", it) }
            initialized = true
        }
    }

    override suspend fun read(): VehicleSnapshot = withContext(Dispatchers.IO) {
        ensureInit()
        // 첫 호출에서 서비스 바인딩이 아직이면 capability 가 비어 오는 경우가 있어 잠깐 양보한다.
        kotlinx.coroutines.delay(120)
        val readings = LinkedHashMap<String, ItemReading>()

        readings += readItem(KEY_OIL_ENGINE) { cb, onFail ->
            vehicle.getSafety().getEngineOilLevelState(cb) { e -> onFail(noteOf(e)) }
        }
        readings += readItem(KEY_OIL_TRANSMISSION) { cb, onFail ->
            vehicle.getSafety().getTransmissionOilLevelState(cb) { e -> onFail(noteOf(e)) }
        }
        readings += readItem(KEY_BRAKE_FLUID) { cb, onFail ->
            vehicle.getSafety().getBrakeFluidLevelState(cb) { e -> onFail(noteOf(e)) }
        }
        readings += readItem(KEY_WASHER) { cb, onFail ->
            vehicle.getSafety().hasLowWasherFluidWarning(cb) { e -> onFail(noteOf(e)) }
        }
        readings += readItem(KEY_POWER_DOWN) { cb, onFail ->
            vehicle.getSafety().hasPowerDownWarningIndicator(cb) { e -> onFail(noteOf(e)) }
        }
        readings += readItem(KEY_SOC) { cb, onFail ->
            vehicle.getEvBattery().getEvBatteryStateOfCharge(cb) { e -> onFail(noteOf(e)) }
        }
        readings += readItem(KEY_FUEL) { cb, onFail ->
            vehicle.getFuel().getFuelPercent(cb) { e -> onFail(noteOf(e)) }
        }
        readings += readItem(KEY_ODOMETER) { cb, onFail ->
            vehicle.getOdometer().getCurrentDistanceDriven(cb) { e -> onFail(noteOf(e)) }
        }
        readings += readAirbag()
        readings += readTireLowPressure()
        readings += readTirePressure()
        readings += readTelltales()

        // 개발문서 6장 4번: 단위 확인 전에는 표시하지 않는다. 확인용으로 처음 한 번만 남긴다.
        readings[KEY_ODOMETER]?.value?.let { distance ->
            logOnce("odometer") { Log.i(TAG, "odometer raw value $distance") }
        }

        // 실차에서 어떤 항목이 읽혔는지 처음 한 번 남긴다(값이 없으면 못 읽은 이유). 기기 로그에만 남는다.
        logOnce("summary") {
            Log.i(TAG, "readings " + readings.values.joinToString(", ") { r -> "${r.key}=${r.value ?: (r.note ?: "null")}" })
        }
        VehicleSnapshot(readings, System.currentTimeMillis(), isDemo = false)
    }

    /** 같은 이름의 로그를 프로세스 수명 동안 한 번만 남긴다(점검은 앱을 켤 때마다 돈다). */
    private fun logOnce(name: String, write: () -> Unit) {
        if (logged.add(name)) write()
    }

    private fun noteOf(e: Exception): String {
        Log.d(TAG, "read failed: ${e.javaClass.simpleName}: ${e.message}")
        return if (e is IllegalArgumentException) "unsupported" else e.javaClass.simpleName
    }

    /** 콜백이 두 번 와도 한 번만 resume 한다. 시간 초과면 value=null. */
    private suspend fun readItem(
        key: String,
        request: (cb: (Any?) -> Unit, onFail: (String) -> Unit) -> Unit,
    ): Pair<String, ItemReading> {
        val result = withTimeoutOrNull(ITEM_TIMEOUT_MS) {
            suspendCancellableCoroutine<ItemReading?> { cont ->
                var resumed = false
                val cb: (Any?) -> Unit = { value ->
                    if (!resumed) {
                        resumed = true
                        if (cont.isActive) cont.resume(toReading(key, value))
                    }
                }
                // SDK 는 실패를 콜백(onFailed)으로 알린다. 이 경로를 "미지원"으로 반드시 표시해야
                // 점수 엔진이 그 항목을 계산에서 빼고, 측정 항목이 3개 미만이면 등급을 내지 않는다.
                val fail: (String) -> Unit = { note ->
                    if (!resumed) {
                        resumed = true
                        if (cont.isActive) {
                            cont.resume(ItemReading(key, supported = false, value = null, note = note))
                        }
                    }
                }
                runCatching { request(cb, fail) }
                    .onFailure { e ->
                        fail(if (e is IllegalArgumentException) "unsupported" else e.javaClass.simpleName)
                    }
            }
        }
        return key to (result ?: ItemReading(key, supported = true, value = null, note = "timeout"))
    }

    private fun toReading(key: String, value: Any?): ItemReading = when (value) {
        is OilLevelState -> oilReading(key, value)
        is Boolean -> ItemReading(key, supported = true, value = if (value) 1.0 else 0.0)
        is Int -> ItemReading(key, supported = true, value = value.toDouble())
        is Float -> ItemReading(key, supported = true, value = value.toDouble())
        null -> ItemReading(key, supported = false, value = null, note = "null")
        else -> ItemReading(key, supported = true, value = null, note = value.javaClass.simpleName)
    }

    private fun oilReading(key: String, state: OilLevelState): ItemReading = when (state) {
        OilLevelState.NORMAL -> ItemReading(key, true, 0.0)
        OilLevelState.LOW -> ItemReading(key, true, 1.0)
        OilLevelState.CRITICALLY_LOW -> ItemReading(key, true, 2.0)
        OilLevelState.HIGH -> ItemReading(key, true, 3.0)
        OilLevelState.ERROR -> ItemReading(key, supported = true, value = null, note = "sensor_error")
    }

    /**
     * 에어백 전개 여부. SDK 는 좌석(SeatArea)별로만 알려준다.
     * 지원 좌석을 모두 읽어 하나라도 전개됐으면 1.0, 전부 아니면 0.0.
     * 지원 좌석을 못 얻거나 하나도 못 읽으면 미지원/미측정으로 남긴다.
     */
    private suspend fun readAirbag(): Pair<String, ItemReading> {
        val seats = withTimeoutOrNull(ITEM_TIMEOUT_MS) {
            suspendCancellableCoroutine<List<SeatArea>?> { cont ->
                runCatching {
                    vehicle.getSafety().checkAirbagDeployedCapabilities(
                        { _, list -> if (cont.isActive) cont.resume(list) },
                        { if (cont.isActive) cont.resume(null) },
                    )
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
        if (seats == null) {
            return KEY_AIRBAG to ItemReading(KEY_AIRBAG, supported = true, value = null, note = "capability_timeout")
        }
        if (seats.isEmpty()) {
            return KEY_AIRBAG to ItemReading(KEY_AIRBAG, supported = false, value = null, note = "no_seat_area")
        }
        var read = 0
        var deployed = false
        for (seat in seats) {
            val value = withTimeoutOrNull(ITEM_TIMEOUT_MS) {
                suspendCancellableCoroutine<Boolean?> { cont ->
                    vehicle.getSafety().hasSeatAirbagBeenDeployed(
                        seat,
                        { v -> if (cont.isActive) cont.resume(v) },
                        { if (cont.isActive) cont.resume(null) },
                    )
                }
            } ?: continue
            read++
            if (value) deployed = true
        }
        return when {
            // 전개된 좌석이 하나라도 있으면 나머지를 못 읽어도 결론은 같다.
            deployed -> KEY_AIRBAG to ItemReading(KEY_AIRBAG, supported = true, value = 1.0)
            read == seats.size -> KEY_AIRBAG to ItemReading(KEY_AIRBAG, supported = true, value = 0.0)
            read == 0 -> KEY_AIRBAG to ItemReading(KEY_AIRBAG, supported = false, value = null, note = "unreadable")
            else -> KEY_AIRBAG to ItemReading(KEY_AIRBAG, supported = true, value = null, note = "partial")
        }
    }

    private suspend fun readTireLowPressure(): Pair<String, ItemReading> {
        val areas = withTimeoutOrNull(ITEM_TIMEOUT_MS) {
            suspendCancellableCoroutine<List<WheelArea>?> { cont ->
                vehicle.getTire().checkWheelAreaCapabilities(
                    { list -> if (cont.isActive) cont.resume(list) },
                    { if (cont.isActive) cont.resume(null) },
                )
            }
        }
        // capability 호출이 시간 초과(null)면 "미지원"이 아니라 "이번에 못 읽음"이다.
        // 빈 목록일 때만 이 차가 타이어를 지원하지 않는 것으로 본다(G0: WheelArea 4개 반환 확인).
        if (areas == null) {
            return KEY_TIRE_LOW to ItemReading(KEY_TIRE_LOW, supported = true, value = null, note = "capability_timeout")
        }
        if (areas.isEmpty()) {
            return KEY_TIRE_LOW to ItemReading(KEY_TIRE_LOW, supported = false, value = null, note = "no_wheel_area")
        }
        val pairs = mutableListOf<Pair<Float?, Float?>>()
        val mapped = linkedMapOf<moca.core.WheelArea, Pair<Float?, Float?>>()
        val wheelLog = mutableListOf<String>()
        for (area in areas) {
            val pressure = readFloat { onOk, onFail ->
                vehicle.getTire().getTirePressure(area, onOk, onFail)
            }
            val threshold = readFloat { onOk, onFail ->
                vehicle.getTire().getLowTirePressure(area, onOk, onFail)
            }
            val pair = pressure to threshold
            pairs += pair
            val core = toCoreWheel(area)
            if (core != null) mapped[core] = pair
            wheelLog += "${core ?: area.values.joinToString("/")}=${pressure ?: "-"}/${threshold ?: "-"}"
        }
        // 개발문서 2.3절·6장 2번: 바퀴별 현재 공기압과 기준값을 실제 값으로 확인한다.
        // 두 값 모두 0/1 로만 오면 "현재 < 기준" 판정을 다시 정해야 한다.
        logOnce("tire") {
            Log.i(TAG, "tire pressure/threshold kPa ${wheelLog.joinToString(", ")}")
        }
        val lowCount = moca.core.TirePressure.lowCount(pairs)
        val named = moca.core.TirePressure.lowAreas(mapped)
        if (lowCount == null || named == null || lowCount != named.size) {
            val note = if (lowCount != null && named != null) "unmapped_wheel" else "timeout"
            return KEY_TIRE_LOW to ItemReading(KEY_TIRE_LOW, supported = true, value = null, note = note)
        }
        return KEY_TIRE_LOW to ItemReading(
            KEY_TIRE_LOW,
            supported = true,
            value = lowCount.toDouble(),
            note = wheelNote(named),
        )
    }

    /**
     * SDK `WheelValue` 이름과 core 바퀴를 맞춘다.
     * LEFT_FRONT→FL, RIGHT_FRONT→FR, LEFT_REAR→RL, RIGHT_REAR→RR.
     * UNKNOWN 이나 바퀴가 하나가 아닌 영역은 위치를 만들지 않는다.
     */
    private fun toCoreWheel(area: WheelArea): moca.core.WheelArea? {
        // 에뮬레이터는 영역 하나를 [UNKNOWN, LEFT_FRONT] 처럼 준다(2026-11-14 실측). UNKNOWN 을 빼고 하나만 남을 때 그 바퀴다.
        val values = area.values.filter { it != WheelValue.UNKNOWN }
        if (values.size != 1) return null
        return when (values[0]) {
            WheelValue.LEFT_FRONT -> moca.core.WheelArea.FL
            WheelValue.RIGHT_FRONT -> moca.core.WheelArea.FR
            WheelValue.LEFT_REAR -> moca.core.WheelArea.RL
            WheelValue.RIGHT_REAR -> moca.core.WheelArea.RR
            WheelValue.UNKNOWN -> null
            else -> null
        }
    }

    private fun wheelNote(areas: Set<moca.core.WheelArea>): String? {
        if (areas.isEmpty()) return null
        return moca.core.WheelArea.entries.filter { it in areas }.joinToString("+") { it.name }
    }

    private suspend fun readFloat(
        request: ((Float?) -> Unit, (Exception) -> Unit) -> Unit,
    ): Float? = withTimeoutOrNull(ITEM_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            runCatching {
                request(
                    { value -> if (cont.isActive) cont.resume(value) },
                    { e ->
                        Log.d(TAG, "float read failed: ${e.javaClass.simpleName}: ${e.message}")
                        if (cont.isActive) cont.resume(null)
                    },
                )
            }.onFailure { e ->
                Log.d(TAG, "float read threw: ${e.javaClass.simpleName}: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private suspend fun readTirePressure(): Pair<String, ItemReading> {
        val areas = withTimeoutOrNull(ITEM_TIMEOUT_MS) {
            suspendCancellableCoroutine<List<WheelArea>?> { cont ->
                vehicle.getTire().checkWheelAreaCapabilities(
                    { list -> if (cont.isActive) cont.resume(list) },
                    { if (cont.isActive) cont.resume(null) },
                )
            }
        }
        if (areas == null) return KEY_TIRE_KPA to ItemReading(KEY_TIRE_KPA, true, null, "capability_timeout")
        val first = areas.firstOrNull() ?: return KEY_TIRE_KPA to ItemReading(KEY_TIRE_KPA, false, null, "no_wheel_area")
        val value = withTimeoutOrNull(ITEM_TIMEOUT_MS) {
            suspendCancellableCoroutine<Float?> { cont ->
                vehicle.getTire().getTirePressure(
                    first,
                    { v -> if (cont.isActive) cont.resume(v) },
                    { if (cont.isActive) cont.resume(null) },
                )
            }
        }
        return KEY_TIRE_KPA to ItemReading(KEY_TIRE_KPA, supported = true, value = value?.toDouble())
    }

    private suspend fun readTelltales(): Pair<String, ItemReading> {
        val telltales = withTimeoutOrNull(ITEM_TIMEOUT_MS) {
            suspendCancellableCoroutine<Telltales?> { cont ->
                vehicle.getSafety().getTelltalesInfo(
                    { t -> if (cont.isActive) cont.resume(t) },
                    { if (cont.isActive) cont.resume(null) },
                )
            }
        }
        if (telltales == null) {
            return KEY_TELLTALES to ItemReading(KEY_TELLTALES, supported = false, value = null, note = "unavailable")
        }
        val summary = summarize(telltales.telltales, isDemo = false)
        warningCache = summary
        // 경고 개수는 별도 키로, 색 분류는 WarningSummary 로 남긴다.
        return KEY_TELLTALES to ItemReading(KEY_TELLTALES, supported = true, value = summary.total.toDouble())
    }

    override fun cachedWarnings(): WarningSummary = warningCache

    override fun startWatchingWarnings(onChange: (WarningSummary) -> Unit) {
        ensureInit()
        if (listener != null) return
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val wrapper = object : TelltaleInfoListener {
            override fun onTelltaleInfoUpdated(telltales: Telltales?) {
                val summary = summarize(telltales?.telltales.orEmpty(), isDemo = false)
                warningCache = summary
                main.post { onChange(summary) }
            }

            override fun onFailed(e: Exception) {
                Log.w(TAG, "telltale listener failed: ${e.javaClass.simpleName}")
            }
        }
        listener = wrapper
        runCatching { vehicle.getSafety().registerTelltales(wrapper) }
            .onFailure { Log.e(TAG, "registerTelltales failed", it) }
    }

    override fun stopWatchingWarnings() {
        val current = listener ?: return
        runCatching { vehicle.getSafety().unregisterTelltales(current) }
        listener = null
    }

    private fun summarize(list: List<TelltaleData>, isDemo: Boolean): WarningSummary {
        val actives = mutableListOf<ActiveWarning>()
        for (t in list) {
            if (t.state == TelltaleState.OFF) continue
            actives += ActiveWarning(
                type = t.type.name,
                color = t.color,
                blinking = t.state == TelltaleState.BLINK,
            )
        }
        return WarningCounter.summarize(actives, isDemo)
    }

    companion object {
        private const val TAG = "MOCA_VEHICLE"
        private const val ITEM_TIMEOUT_MS = 1500L

        const val KEY_OIL_ENGINE = "oil_engine"
        const val KEY_OIL_TRANSMISSION = "oil_transmission"
        const val KEY_BRAKE_FLUID = "brake_fluid"
        const val KEY_WASHER = "washer"
        const val KEY_POWER_DOWN = "power_down"
        const val KEY_AIRBAG = "airbag"
        const val KEY_SOC = "soc"
        const val KEY_FUEL = "fuel"
        const val KEY_ODOMETER = "odometer"
        const val KEY_TIRE_LOW = "tire_low"
        const val KEY_TIRE_KPA = "tire_kpa"
        const val KEY_TELLTALES = "telltales"
    }
}

enum class Severity { RED, YELLOW, NONE }

/**
 * 경고등 색 정수 → RED/YELLOW 매핑.
 *
 * ⚠️ **미확인**: Pleos 에뮬레이터의 Virtual Console DDS 브리지가 비활성이라
 * ABS를 켜서 실제 정수 값을 얻지 못했다(docs/evidence/G0/report.md §4).
 * AAR에는 `TelltaleColor` enum(WHITE·GREEN·BLUE·YELLOW·RED·GRAY)이 있으나
 * 그 값과 `TelltaleData.color` 의 정수 대응은 internal API라 확인할 수 없다.
 *
 * 그래서 여기서만 가정하고, 가정이 틀려도 다른 코드로 번지지 않게 격리한다.
 * 실값을 얻으면 이 파일만 고치면 된다.
 */
object TelltaleColorMap {

    /** 실제 정수 값을 얻지 못해 쓰는 임시 대응. 문서에 "가정"으로 표기한다. */
    private val assumed: Map<Int, Severity> = mapOf(
        4 to Severity.RED,    // TelltaleColor.RED ordinal 가정
        3 to Severity.YELLOW, // TelltaleColor.YELLOW ordinal 가정
    )

    fun severity(color: Int): Severity = assumed[color] ?: Severity.NONE
}
