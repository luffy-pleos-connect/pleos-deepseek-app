package ai.moca.app.voice

import ai.moca.app.inspect.InspectionResult
import ai.moca.app.nav.PlaceKind
import ai.moca.app.vehicle.ItemReading
import ai.moca.app.vehicle.PleosVehicleSource
import ai.moca.app.vehicle.Severity
import ai.moca.app.vehicle.TelltaleColorMap
import ai.moca.app.vehicle.TelltaleTypeMap
import ai.moca.app.vehicle.WarningSummary
import moca.core.Category
import moca.core.Deduction
import moca.core.Evaluation
import moca.core.FluidLevel
import moca.core.Item
import moca.core.Priority
import moca.core.Rules
import moca.core.VehicleSnapshot
import moca.core.WheelArea
import org.json.JSONArray
import org.json.JSONObject

/**
 * 차량 도구 10개. LLM(도구 호출)과 오프라인 의도가 **같은 함수**를 부른다.
 * 결과는 JSON 문자열이며 VIN·기기 ID·위치·좌표를 담지 않는다.
 *
 * 차의 값은 «가장 최근 점검»([latest])에서만 읽는다. 물을 때마다 차를 다시 읽지 않는다.
 * 다시 점검하는 것은 run_inspection 뿐이다.
 * 길 안내 도구는 내비를 직접 만지지 않고 화면에 요청을 넘긴다([requestNav]).
 */
class Tools(
    private val latest: () -> InspectionResult?,
    private val isDemo: Boolean,
    private val drivingLimited: () -> Boolean,
    private val openScreen: (String) -> Boolean,
    private val requestNav: (NavRequest) -> String,
    private val rerunInspection: suspend () -> Unit = {},
    private val startBattle: () -> Unit = {},
) {

    data class Snapshot(
        val readings: Map<String, ItemReading>,
        val warnings: WarningSummary,
        val evaluation: Evaluation?,
        val engine: VehicleSnapshot,
        val odometer: Double?,
    )

    private fun snapshot(): Snapshot? = latest()?.let {
        Snapshot(it.readings, it.warnings, it.evaluation, it.engine, it.distanceDriven)
    }

    private fun noMeasurement(): String = JSONObject().put("ok", false).put("reason", "no_measurement").toString()

    /** 도구 정의(OpenAI 형식). 서버에 그대로 보낸다. */
    fun definitions(): JSONArray = JSONArray().apply {
        put(fn("get_latest_inspection", "최신 점검 결과(등급·종합·건강·관리·카테고리 5)를 돌려준다.", obj()))
        put(fn("get_active_warnings", "지금 켜진 경고등의 이름·색·개수를 돌려준다.", obj()))
        put(fn("run_inspection", "차량을 다시 점검해 새 결과를 돌려준다.", obj()))
        put(
            fn(
                "get_part_status",
                "부품 원본값과 지원 여부를 돌려준다.",
                obj().put("category", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray(listOf("power", "brake", "tire", "electric", "safety")))
                    put("description", "생략하면 전체")
                }),
            ),
        )
        put(fn("get_energy_and_distance", "연료·배터리 잔량과 누적 주행거리를 돌려준다. 주행 가능 거리는 알 수 없다.", obj()))
        put(fn("start_battle", "대전하기 화면을 연다. 대전은 운전자가 시작 단추를 눌러 시작한다.", obj()))
        put(
            fn(
                "open_screen",
                "앱 화면을 연다. 주행 중이면 열 수 없다.",
                obj().put("screen", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray(listOf("home", "status", "battle", "settings")))
                }),
            ),
        )
        put(
            fn(
                "start_navigation_to_service",
                "차량 내비로 길 안내를 요청한다. 운전자에게 확인을 받은 뒤 시작한다. place 가 있으면 그 장소로, 없으면 가까운 kind 로 안내한다.",
                obj().put("kind", kindParam()).put("place", JSONObject().apply {
                    put("type", "string")
                    put("description", "운전자가 말한 장소 이름. 있으면 kind 보다 우선")
                }),
            ),
        )
        put(
            fn(
                "add_stop",
                "안내 중인 길에 가까운 장소를 경유지로 넣는다. 운전자에게 확인을 받은 뒤 넣는다.",
                obj().put("kind", kindParam()),
            ),
        )
        put(fn("cancel_navigation", "진행 중인 길 안내를 끈다.", obj()))
    }

    private fun kindParam(): JSONObject = JSONObject().apply {
        put("type", "string")
        put("enum", JSONArray(PlaceKind.entries.map { it.name.lowercase() }))
        put("description", "생략하면 repair_shop")
    }

    private fun fn(name: String, description: String, params: JSONObject): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", params)
                put("additionalProperties", false)
            })
        })
    }

    private fun obj(): JSONObject = JSONObject()

    /** 도구 하나를 실행해 결과 JSON 문자열을 돌려준다. 예외도 JSON 으로 돌려준다. */
    suspend fun execute(name: String, args: JSONObject?): String = runCatching {
        when (name) {
            "get_latest_inspection" -> latestInspection()
            "get_active_warnings" -> activeWarnings()
            "run_inspection" -> {
                rerunInspection()
                latestInspection()
            }
            "get_part_status" -> partStatus(args?.optString("category").orEmpty())
            "get_energy_and_distance" -> energyAndDistance()
            "start_battle" -> startBattleTool()
            "open_screen" -> openScreenTool(args?.optString("screen").orEmpty())
            "start_navigation_to_service" -> requestNav(goRequest(args))
            "add_stop" -> requestNav(NavRequest(NavAction.ADD_STOP, kind = kindOf(args)))
            "cancel_navigation" -> requestNav(NavRequest(NavAction.CANCEL))
            else -> JSONObject().put("ok", false).put("error", "unknown_tool").put("name", name).toString()
        }
    }.getOrElse { e ->
        JSONObject().put("ok", false).put("error", e.javaClass.simpleName).toString()
    }

    private fun kindOf(args: JSONObject?): PlaceKind =
        PlaceKind.entries.firstOrNull { it.name.equals(args?.optString("kind"), ignoreCase = true) } ?: PlaceKind.REPAIR_SHOP

    /** 말한 장소 이름이 있으면 그것이 종류보다 먼저다. */
    private fun goRequest(args: JSONObject?): NavRequest {
        val place = args?.optString("place")?.trim().orEmpty()
        return if (place.isNotEmpty()) NavRequest(NavAction.GO, keyword = place) else NavRequest(NavAction.GO, kind = kindOf(args))
    }

    private fun latestInspection(): String {
        val s = snapshot() ?: return noMeasurement()
        val eval = s.evaluation ?: return noMeasurement()
        return JSONObject().apply {
            put("ok", true)
            put("grade", eval.grade?.label ?: JSONObject.NULL)
            put("overall", eval.displayedOverall ?: JSONObject.NULL)
            put("health", eval.health?.toInt() ?: JSONObject.NULL)
            put("care", eval.care ?: JSONObject.NULL)
            put("healthNotice", eval.healthNotice ?: JSONObject.NULL)
            put("redCapNotice", eval.redCapNotice ?: JSONObject.NULL)
            put("nextGrade", eval.nextGrade?.label ?: JSONObject.NULL)
            put("pointsToNext", eval.pointsToNext ?: JSONObject.NULL)
            put("categories", JSONObject().apply {
                for (c in Category.entries) put(key(c), eval.categories[c] ?: JSONObject.NULL)
            })
            put("batteryPercent", s.readings[PleosVehicleSource.KEY_SOC]?.value ?: JSONObject.NULL)
            // 기기 안의 답도 서버 답처럼 정차 안내를 앞에 붙이는지 가릴 때 쓴다.
            put("red", s.warnings.redCount)
        }.toString()
    }

    private fun activeWarnings(): String {
        val s = snapshot() ?: return noMeasurement()
        return JSONObject().apply {
            put("ok", true)
            put("red", s.warnings.redCount)
            put("yellow", s.warnings.yellowCount)
            put("total", s.warnings.total)
            put("active", warningArray(s.warnings))
            // Tools 는 Context 가 없다. 문장은 strings.xml 의 c14_stop_safely 와 같다.
            put("safetyAdvice", if (s.warnings.redCount > 0) STOP_SAFELY else JSONObject.NULL)
        }.toString()
    }

    private fun partStatus(category: String): String {
        val s = snapshot() ?: return noMeasurement()
        val engine = s.engine
        val all = JSONObject().apply {
            putFluid(
                PleosVehicleSource.KEY_OIL_ENGINE,
                s.readings,
                engine.engineOil,
                Item.ENGINE_OIL,
                "ENGINE_OIL",
                Category.POWER,
                "엔진오일",
                engine,
            )
            putFluid(
                PleosVehicleSource.KEY_BRAKE_FLUID,
                s.readings,
                engine.brakeFluid,
                Item.BRAKE_FLUID,
                "BRAKE_FLUID",
                Category.BRAKE_STEERING,
                "브레이크액",
                engine,
            )
            putTire(s.readings, engine)
            putBattery(s.readings, engine)
            putWasher(s.readings, engine)
            putItem(PleosVehicleSource.KEY_TELLTALES, s.readings)
        }
        val filtered = if (category.isBlank()) {
            all
        } else {
            JSONObject().apply {
                keysFor(category).forEach { key ->
                    if (all.has(key)) put(key, all.get(key))
                }
            }
        }
        return JSONObject().apply {
            put("ok", true)
            put("items", filtered)
            put("problems", JSONArray().apply {
                for (d in Rules.deductions(engine)) put(d.label)
            })
            put("red", s.warnings.redCount)
        }.toString()
    }

    /**
     * 경고등 type → 한국어 이름.
     * 종류 대응은 [TelltaleTypeMap] 한 곳에만 있다. 대응이 없는 type 은 원문 그대로 둔다.
     */
    private fun telltaleLabel(type: String): String =
        TelltaleTypeMap.toEngine(type)?.label ?: type

    /** 색 정수는 PleosVehicleSource.TelltaleColorMap 과 같다. 4=빨강, 3=노랑. 그 밖은 이름을 붙이지 않는다. */
    private fun colorWord(color: Int): String? = when (TelltaleColorMap.severity(color)) {
        Severity.RED -> "빨강"
        Severity.YELLOW -> "노랑"
        Severity.NONE -> null
    }

    private fun warningArray(warnings: WarningSummary): JSONArray = JSONArray().apply {
        for (w in warnings.active) {
            put(JSONObject().apply {
                put("name", telltaleLabel(w.type))
                put("color", colorWord(w.color) ?: JSONObject.NULL)
            })
        }
    }

    private fun JSONObject.putFluid(
        key: String,
        readings: Map<String, ItemReading>,
        level: FluidLevel,
        item: Item,
        group: String,
        category: Category,
        label: String,
        engine: VehicleSnapshot,
    ) {
        val reading = readings[key] ?: return
        val obj = itemJson(reading)
        if (fluidReadable(reading, level, item, engine)) {
            val state = when (level) {
                FluidLevel.NORMAL -> "정상"
                FluidLevel.LOW -> "부족"
                FluidLevel.CRITICALLY_LOW -> "심각 부족"
                FluidLevel.HIGH -> "과다"
                FluidLevel.ERROR -> null
            }
            if (state != null) {
                obj.put("state", state)
                if (state != "정상") {
                    obj.put("advice", Priority.advice(Deduction(group, category, 0, label), engine))
                }
            }
        }
        put(key, obj)
    }

    private fun fluidReadable(
        reading: ItemReading,
        level: FluidLevel,
        item: Item,
        engine: VehicleSnapshot,
    ): Boolean {
        if (!reading.supported || reading.value == null || reading.note == "sensor_error") return false
        if (item in engine.unsupported || level == FluidLevel.ERROR) return false
        return true
    }

    private fun JSONObject.putTire(readings: Map<String, ItemReading>, engine: VehicleSnapshot) {
        val key = PleosVehicleSource.KEY_TIRE_LOW
        val reading = readings[key] ?: return
        val obj = itemJson(reading)
        val value = reading.value
        if (reading.supported && value != null && Item.TIRE !in engine.unsupported) {
            val wheels = engine.lowTireWheels
            if (wheels.isEmpty() && value == 0.0) {
                obj.put("state", "정상")
            } else {
                obj.put("state", "저압")
                obj.put("places", JSONArray().apply {
                    for (wheel in WheelArea.entries) {
                        if (wheel in wheels) put(wheel.label)
                    }
                })
                obj.put(
                    "advice",
                    Priority.advice(Deduction("TIRE_PRESSURE", Category.TIRE, 0, "타이어 저압"), engine),
                )
            }
        }
        put(key, obj)
    }

    private fun JSONObject.putBattery(readings: Map<String, ItemReading>, engine: VehicleSnapshot) {
        val key = PleosVehicleSource.KEY_SOC
        val reading = readings[key] ?: return
        val obj = itemJson(reading)
        val value = reading.value
        if (reading.supported && value != null && Item.ENERGY !in engine.unsupported) {
            if (value < LOW_BATTERY_PERCENT) {
                obj.put("state", "부족")
                obj.put(
                    "advice",
                    Priority.advice(Deduction("ENERGY", Category.ELECTRIC, 0, "배터리"), engine),
                )
            } else {
                obj.put("state", "정상")
            }
        }
        put(key, obj)
    }

    private fun JSONObject.putWasher(readings: Map<String, ItemReading>, engine: VehicleSnapshot) {
        val key = PleosVehicleSource.KEY_WASHER
        val reading = readings[key] ?: return
        val obj = itemJson(reading)
        if (reading.supported && reading.value != null && Item.WASHER_FLUID !in engine.unsupported) {
            if (engine.washerFluidLow) {
                obj.put("state", "부족")
                obj.put(
                    "advice",
                    Priority.advice(Deduction("WASHER", Category.SAFETY, 0, "워셔액"), engine),
                )
            } else {
                obj.put("state", "정상")
            }
        }
        put(key, obj)
    }

    private fun itemJson(reading: ItemReading): JSONObject = JSONObject().apply {
        put("supported", reading.supported)
        put("value", reading.value ?: JSONObject.NULL)
        if (reading.note != null) put("note", reading.note)
    }

    private fun keysFor(category: String): List<String> = when (category) {
        "power" -> listOf(PleosVehicleSource.KEY_OIL_ENGINE)
        "brake" -> listOf(PleosVehicleSource.KEY_BRAKE_FLUID)
        "tire" -> listOf(PleosVehicleSource.KEY_TIRE_LOW)
        "electric" -> listOf(PleosVehicleSource.KEY_SOC)
        "safety" -> listOf(PleosVehicleSource.KEY_TELLTALES, PleosVehicleSource.KEY_WASHER)
        else -> emptyList()
    }

    private fun JSONObject.putItem(key: String, readings: Map<String, ItemReading>) {
        val r = readings[key] ?: return
        put(key, itemJson(r))
    }

    /** 읽힌 잔량만 넣는다. 주행 가능 거리는 읽을 수 없고, 주행거리는 단위를 확인하지 못했다. */
    private fun energyAndDistance(): String {
        val s = snapshot() ?: return noMeasurement()
        return JSONObject().apply {
            put("ok", true)
            readPercent(s, PleosVehicleSource.KEY_FUEL)?.let { put("fuelPercent", it) }
            readPercent(s, PleosVehicleSource.KEY_SOC)?.let { put("batteryPercent", it) }
            s.odometer?.let { put("odometer", it) }
            put("odometerUnit", "unverified")
        }.toString()
    }

    private fun readPercent(s: Snapshot, key: String): Double? =
        s.readings[key]?.takeIf { it.supported }?.value

    /** 대전하기 화면만 연다. 대전은 운전자가 시작 단추를 눌러 시작한다. */
    private fun startBattleTool(): String {
        if (drivingLimited()) {
            return JSONObject().put("ok", false).put("reason", "driving_limited").toString()
        }
        startBattle()
        return JSONObject().put("ok", true).put("opened", "battle").toString()
    }

    private fun openScreenTool(screen: String): String {
        if (drivingLimited()) {
            return JSONObject().put("ok", false).put("reason", "driving_limited").toString()
        }
        val ok = openScreen(screen)
        return JSONObject().put("ok", ok).put("screen", screen).toString()
    }

    private fun key(category: Category): String = when (category) {
        Category.POWER -> "power"
        Category.BRAKE_STEERING -> "brake"
        Category.TIRE -> "tire"
        Category.ELECTRIC -> "electric"
        Category.SAFETY -> "safety"
    }

    companion object {
        /** res/values/strings.xml 의 c14_stop_safely 와 같은 문장. */
        const val STOP_SAFELY = "안전한 곳에 정차한 뒤 점검을 받으세요"

        /** Priority 의 배터리 부족 기준과 같다. */
        private const val LOW_BATTERY_PERCENT = 10.0
    }
}
