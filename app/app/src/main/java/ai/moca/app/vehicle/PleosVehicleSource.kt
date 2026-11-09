package ai.moca.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.telltale.TelltaleData
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

        readings += readItem(KEY_OIL_ENGINE) { cb -> vehicle.getSafety().getEngineOilLevelState(cb, ::fail) }
        readings += readItem(KEY_BRAKE_FLUID) { cb -> vehicle.getSafety().getBrakeFluidLevelState(cb, ::fail) }
        readings += readItem(KEY_WASHER) { cb -> vehicle.getSafety().hasLowWasherFluidWarning(cb, ::fail) }
        readings += readItem(KEY_POWER_DOWN) { cb -> vehicle.getSafety().hasPowerDownWarningIndicator(cb, ::fail) }
        readings += readItem(KEY_SOC) { cb -> vehicle.getEvBattery().getEvBatteryStateOfCharge(cb, ::fail) }
        readings += readTireLowPressure()
        readings += readTirePressure()
        readings += readTelltales()

        VehicleSnapshot(readings, System.currentTimeMillis(), isDemo = false)
    }

    private fun fail(e: Exception) {
        Log.d(TAG, "read failed: ${e.javaClass.simpleName}: ${e.message}")
    }

    /** 콜백이 두 번 와도 한 번만 resume 한다. 시간 초과면 value=null. */
    private suspend fun readItem(
        key: String,
        request: (cb: (Any?) -> Unit) -> Unit,
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
                runCatching { request(cb) }
                    .onFailure {
                        if (!resumed) {
                            resumed = true
                            if (cont.isActive) cont.resume(ItemReading(key, supported = false, value = null, note = "unsupported"))
                        }
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
        var lowCount = 0
        var readAny = false
        for (area in areas) {
            val value = withTimeoutOrNull(ITEM_TIMEOUT_MS) {
                suspendCancellableCoroutine<Float?> { cont ->
                    vehicle.getTire().getLowTirePressure(
                        area,
                        { v -> if (cont.isActive) cont.resume(v) },
                        { if (cont.isActive) cont.resume(null) },
                    )
                }
            }
            if (value != null) {
                readAny = true
                if (value > 0f) lowCount++
            }
        }
        return if (!readAny) {
            KEY_TIRE_LOW to ItemReading(KEY_TIRE_LOW, supported = true, value = null, note = "timeout")
        } else {
            KEY_TIRE_LOW to ItemReading(KEY_TIRE_LOW, supported = true, value = lowCount.toDouble())
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
        var red = 0
        var yellow = 0
        var active = 0
        for (t in list) {
            if (t.state == TelltaleState.OFF) continue
            active++
            when (TelltaleColorMap.severity(t.color)) {
                Severity.RED -> red++
                Severity.YELLOW -> yellow++
                Severity.NONE -> Unit
            }
        }
        return WarningSummary(red, yellow, active, isDemo)
    }

    companion object {
        private const val TAG = "MOCA_VEHICLE"
        private const val ITEM_TIMEOUT_MS = 1500L

        const val KEY_OIL_ENGINE = "oil_engine"
        const val KEY_BRAKE_FLUID = "brake_fluid"
        const val KEY_WASHER = "washer"
        const val KEY_POWER_DOWN = "power_down"
        const val KEY_SOC = "soc"
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
