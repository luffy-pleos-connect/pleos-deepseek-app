package ai.moca.app.voice

import ai.moca.app.vehicle.ItemReading
import ai.moca.app.vehicle.PleosVehicleSource
import ai.moca.app.vehicle.VehicleSource
import ai.moca.app.vehicle.WarningSummary
import moca.core.Category
import moca.core.Evaluation
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
        put(fn("get_active_warnings", "지금 켜진 경고등의 개수와 색 요약을 돌려준다.", obj()))
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
                    put("enum", JSONArray(listOf("home", "status", "battle")))
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
            "run_inspection" -> latestInspection()
            "get_part_status" -> partStatus(args?.optString("category").orEmpty())
            "get_energy_and_distance" -> energyAndDistance()
            "start_battle" -> JSONObject().put("ok", false).put("reason", "battle_not_ready").toString()
            "open_screen" -> openScreenTool(args?.optString("screen").orEmpty())
            "start_navigation_to_service" -> JSONObject().put("ok", false).put("reason", "not_implemented").toString()
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
            put("safetyAdvice", if (s.warnings.redCount > 0) ai.moca.app.R.string.c14_stop_safely else JSONObject.NULL)
        }.toString()
    }

    private suspend fun partStatus(category: String): String {
        val s = snapshot()
        val all = JSONObject().apply {
            putItem(PleosVehicleSource.KEY_OIL_ENGINE, s.readings)
            putItem(PleosVehicleSource.KEY_BRAKE_FLUID, s.readings)
            putItem(PleosVehicleSource.KEY_TIRE_LOW, s.readings)
            putItem(PleosVehicleSource.KEY_SOC, s.readings)
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
        }.toString()
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
        put(key, JSONObject().apply {
            put("supported", r.supported)
            put("value", r.value ?: JSONObject.NULL)
            if (r.note != null) put("note", r.note)
        })
    }

    private suspend fun energyAndDistance(): String {
        val s = snapshot()
        return JSONObject().apply {
            put("ok", true)
            put("isDemo", isDemo)
            put("batteryPercent", s.readings[PleosVehicleSource.KEY_SOC]?.value ?: JSONObject.NULL)
        }.toString()
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
}
