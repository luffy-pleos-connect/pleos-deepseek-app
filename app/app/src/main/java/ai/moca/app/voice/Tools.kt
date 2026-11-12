package ai.moca.app.voice

import ai.moca.app.score.ScoreBridge
import ai.moca.app.vehicle.ItemReading
import ai.moca.app.vehicle.PleosVehicleSource
import ai.moca.app.vehicle.Severity
import ai.moca.app.vehicle.TelltaleColorMap
import ai.moca.app.vehicle.VehicleSource
import ai.moca.app.vehicle.WarningSummary
import moca.core.Category
import moca.core.Deduction
import moca.core.Evaluation
import moca.core.FixAction
import moca.core.FluidLevel
import moca.core.Item
import moca.core.Priority
import moca.core.Rules
import moca.core.Telltale
import moca.core.VehicleSnapshot
import moca.core.WheelArea
import org.json.JSONArray
import org.json.JSONObject

/**
 * 차량 도구 8개. LLM(도구 호출)과 오프라인 의도가 **같은 함수**를 부른다.
 * 결과는 JSON 문자열이며 VIN·기기 ID·개인정보를 담지 않는다.
 */
class Tools(
    private val vehicle: VehicleSource,
    private val isDemo: Boolean,
    private val drivingLimited: () -> Boolean,
    private val openScreen: (String) -> Boolean,
    private val evaluate: (Map<String, ItemReading>, WarningSummary, Boolean) -> Evaluation?,
    private val startFix: suspend (FixAction) -> String,
    private val rerunInspection: suspend () -> Unit = {},
    private val startBattle: () -> Unit = {},
) {

    data class Snapshot(
        val readings: Map<String, ItemReading>,
        val warnings: WarningSummary,
        val evaluation: Evaluation?,
    )

    private suspend fun snapshot(): Snapshot {
        val readings = vehicle.read()
        val warnings = vehicle.cachedWarnings()
        return Snapshot(readings.items, warnings, evaluate(readings.items, warnings, readings.isDemo))
    }

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
        put(fn("get_energy_and_distance", "배터리·연료 잔량과 주행거리를 돌려준다.", obj()))
        put(fn("start_battle", "부품 대전을 시작한다.", obj()))
        put(
            fn(
                "open_screen",
                "앱 화면을 연다. 주행 중이면 열 수 없다.",
                obj().put("screen", JSONObject().apply {
                    put("type", "string")
                    put("enum", JSONArray(listOf("home", "status", "battle", "settings", "inspect")))
                }),
            ),
        )
        put(fn("start_navigation_to_service", "가까운 정비소로 경로를 시작한다.", obj()))
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
            "start_navigation_to_service" -> startFix(FixAction.REPAIR_SHOP)
            else -> JSONObject().put("ok", false).put("error", "unknown_tool").put("name", name).toString()
        }
    }.getOrElse { e ->
        JSONObject().put("ok", false).put("error", e.javaClass.simpleName).toString()
    }

    private suspend fun latestInspection(): String {
        val s = snapshot()
        val eval = s.evaluation ?: return JSONObject().put("ok", false).put("reason", "no_measurement").toString()
        return JSONObject().apply {
            put("ok", true)
            put("isDemo", isDemo)
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

    private suspend fun activeWarnings(): String {
        val s = snapshot()
        return JSONObject().apply {
            put("ok", true)
            put("isDemo", isDemo)
            put("red", s.warnings.redCount)
            put("yellow", s.warnings.yellowCount)
            put("total", s.warnings.total)
            put("active", warningArray(s.warnings))
            // Tools 는 Context 가 없다. 문장은 strings.xml 의 c14_stop_safely 와 같다.
            put("safetyAdvice", if (s.warnings.redCount > 0) STOP_SAFELY else JSONObject.NULL)
        }.toString()
    }

    private suspend fun partStatus(category: String): String {
        val s = snapshot()
        val engine = ScoreBridge.toEngineSnapshot(s.readings, s.warnings, isDemo)
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
            put("isDemo", isDemo)
            put("items", filtered)
            put("problems", JSONArray().apply {
                for (d in Rules.deductions(engine)) put(d.label)
            })
            put("red", s.warnings.redCount)
        }.toString()
    }

    /**
     * 경고등 type → 한국어 이름.
     * ScoreBridge 의 비공개 매핑과 같은 대응이다. 없는 type 은 원문 그대로 둔다.
     */
    private fun telltaleLabel(type: String): String {
        val telltale = when (type.uppercase()) {
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
        return telltale?.label ?: type
    }

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
        "safety" -> listOf(PleosVehicleSource.KEY_TELLTALES)
        else -> emptyList()
    }

    private fun JSONObject.putItem(key: String, readings: Map<String, ItemReading>) {
        val r = readings[key] ?: return
        put(key, itemJson(r))
    }

    private suspend fun energyAndDistance(): String {
        val s = snapshot()
        return JSONObject().apply {
            put("ok", true)
            put("isDemo", isDemo)
            put("batteryPercent", s.readings[PleosVehicleSource.KEY_SOC]?.value ?: JSONObject.NULL)
        }.toString()
    }

    private fun startBattleTool(): String {
        if (drivingLimited()) {
            return JSONObject().put("ok", false).put("reason", "driving_limited").toString()
        }
        startBattle()
        return JSONObject()
            .put("ok", true)
            .put("action", "start_battle")
            .put("screen", "battle")
            .toString()
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
