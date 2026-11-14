package ai.moca.app.data

import moca.core.Care
import moca.core.Demo
import moca.core.FluidLevel
import moca.core.Inspection
import moca.core.InspectionHistory
import moca.core.Item
import moca.core.Profile
import moca.core.Telltale
import moca.core.TelltaleState
import moca.core.Color
import moca.core.VehicleSnapshot
import moca.core.WheelArea
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** 점검 한 줄. 개발 문서 7장의 필드와 같다. */
data class StoredInspection(
    val profile: Profile,
    val date: LocalDate,
    /** 같은 날 몇 번째 점검인지(1부터). 화면·엔진에는 [InspectionStore.records] 가 목록 차례로 바꿔 넘긴다. */
    val seq: Int,
    val atMillis: Long,
    val scenarioId: String?,
    val snapshot: VehicleSnapshot,
    val distanceDriven: Double?,
)

/**
 * 점검 기록 저장소. 앱 내부 파일 두 개(`inspections_real.json`, `inspections_demo.json`)에
 * 시간순 배열로 쌓는다. 안드로이드 클래스를 쓰지 않으므로 JVM 단위 테스트로 돈다.
 *
 * - 쓰기는 임시 파일에 한 뒤 이름을 바꾼다. 도중에 죽어도 원래 파일이 깨지지 않는다.
 * - 실제 기록은 append 할 때 90일보다 오래된 것을 지운다(딱 90일 전은 남긴다).
 * - 데모 기록은 [demoInspect] 가 시나리오를 바꾸거나 날이 바뀔 때 [Demo.recordsOf] 로 새로 깐다.
 */
class InspectionStore(
    private val dir: File,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /** 한 줄을 붙이고 붙인 줄을 돌려준다. */
    @Synchronized
    fun append(
        profile: Profile,
        snapshot: VehicleSnapshot,
        today: LocalDate,
        scenarioId: String? = null,
        distanceDriven: Double? = null,
    ): StoredInspection {
        val kept = read(profile).filter { row ->
            profile != Profile.REAL || !row.date.isBefore(today.minusDays(RETENTION_DAYS))
        }
        val row = StoredInspection(
            profile = profile,
            date = today,
            seq = kept.count { it.date == today } + 1,
            atMillis = now(),
            scenarioId = scenarioId,
            snapshot = snapshot,
            distanceDriven = distanceDriven,
        )
        write(profile, kept + row)
        return row
    }

    /** 시간순(붙인 차례) 전체. */
    @Synchronized
    fun all(profile: Profile): List<StoredInspection> = read(profile)

    /**
     * 엔진([Care])에 넣는 모양. seq 는 목록에서의 차례(0, 1, 2 …)다.
     * 같은 날의 앞뒤 점검을 가릴 수 있게 저장값(그날 몇 번째) 대신 차례를 쓴다.
     */
    @Synchronized
    fun records(profile: Profile): List<Inspection> =
        read(profile).mapIndexed { index, row ->
            Inspection(
                profile = profile,
                inspectedAt = row.date,
                snapshot = row.snapshot,
                seq = index.toLong(),
                scenarioId = row.scenarioId,
            )
        }

    @Synchronized
    fun latest(profile: Profile): StoredInspection? = read(profile).lastOrNull()

    /** 관리 점수. 기록이 없으면 이번 달 시작값(50)이다. */
    @Synchronized
    fun care(profile: Profile, today: LocalDate): Int =
        Care.evaluate(records(profile), profile, today).score

    /**
     * 데모 점검 한 번. 파일이 비었거나, 다른 시나리오의 것이거나, 마지막 기록이 오늘이 아니면
     * [Demo.recordsOf] 로 새로 깐다. 이미 오늘 이 시나리오로 깔려 있으면 같은 스냅샷을 한 줄 더 붙인다.
     */
    @Synchronized
    fun demoInspect(scenarioId: String, today: LocalDate): StoredInspection {
        val rows = read(Profile.DEMO)
        val last = rows.lastOrNull()
        if (last == null || last.scenarioId != scenarioId || last.date != today) {
            val seeded = seedDemo(scenarioId, today)
            write(Profile.DEMO, seeded)
            return seeded.last()
        }
        return append(Profile.DEMO, Demo.snapshotOf(scenarioId), today, scenarioId = scenarioId)
    }

    /** 데모 D3 의 ABS 를 끈 스냅샷으로 같은 날 한 줄 더 붙인다. */
    @Synchronized
    fun demoResolveD3(today: LocalDate): StoredInspection {
        val rows = read(Profile.DEMO)
        val last = rows.lastOrNull()
        if (last == null || last.scenarioId != SCENARIO_D3 || last.date != today) {
            write(Profile.DEMO, seedDemo(SCENARIO_D3, today))
        }
        return append(
            Profile.DEMO,
            Demo.resolvedD3(today).last().snapshot,
            today,
            scenarioId = SCENARIO_D3,
        )
    }

    @Synchronized
    fun clear() {
        file(Profile.REAL).delete()
        file(Profile.DEMO).delete()
    }

    // ── 데모 기록 깔기 ─────────────────────────────────────────────

    private fun seedDemo(scenarioId: String, today: LocalDate): List<StoredInspection> {
        val counts = HashMap<LocalDate, Int>()
        return Demo.recordsOf(scenarioId, today).map { inspection ->
            val date = inspection.inspectedAt
            val seq = (counts[date] ?: 0) + 1
            counts[date] = seq
            StoredInspection(
                profile = Profile.DEMO,
                date = date,
                seq = seq,
                // 지난 날짜는 그 날 정오(서울). 오늘은 지금.
                atMillis = if (date == today) now() else noon(date),
                scenarioId = scenarioId,
                snapshot = inspection.snapshot,
                distanceDriven = null,
            )
        }
    }

    private fun noon(date: LocalDate): Long =
        date.atTime(12, 0).atZone(SEOUL).toInstant().toEpochMilli()

    // ── 파일 읽기·쓰기 ─────────────────────────────────────────────

    private fun file(profile: Profile): File =
        File(dir, if (profile == Profile.DEMO) DEMO_FILE else REAL_FILE)

    /** 깨진 파일은 빈 기록으로 본다. */
    private fun read(profile: Profile): List<StoredInspection> {
        val target = file(profile)
        if (!target.exists()) return emptyList()
        val text = runCatching { target.readText() }.getOrNull() ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val rows = mutableListOf<StoredInspection>()
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            decode(profile, row)?.let { rows += it }
        }
        return rows
    }

    /** 임시 파일에 쓴 뒤 이름을 바꾼다. 중간에 죽어도 원래 파일이 깨지지 않는다. */
    private fun write(profile: Profile, rows: List<StoredInspection>) {
        val array = JSONArray()
        for (row in rows) array.put(encode(row))
        val text = array.toString()
        val target = file(profile)
        if (!dir.exists()) dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text)
        if (tmp.renameTo(target)) return
        // 이름 바꾸기가 안 되면(파일 시스템이 다르거나 잠김) 원본은 그대로 두고 직접 쓴다.
        target.writeText(text)
        tmp.delete()
    }

    private fun encode(row: StoredInspection): JSONObject = JSONObject().apply {
        put("profile", row.profile.name)
        put("date", row.date.toString())
        put("seq", row.seq)
        put("atMillis", row.atMillis)
        put("scenarioId", row.scenarioId ?: JSONObject.NULL)
        put("snapshot", encodeSnapshot(row.snapshot))
        put("distanceDriven", row.distanceDriven ?: JSONObject.NULL)
    }

    private fun encodeSnapshot(snapshot: VehicleSnapshot): JSONObject = JSONObject().apply {
        put("engineOil", snapshot.engineOil.name)
        put("transmissionOil", snapshot.transmissionOil.name)
        put("brakeFluid", snapshot.brakeFluid.name)
        put("lowTireWheels", JSONArray().apply {
            for (wheel in WheelArea.entries) if (wheel in snapshot.lowTireWheels) put(wheel.name)
        })
        put("fuelPercent", snapshot.fuelPercent ?: JSONObject.NULL)
        put("batteryPercent", snapshot.batteryPercent ?: JSONObject.NULL)
        put("washerFluidLow", snapshot.washerFluidLow)
        put("airbagDeployed", snapshot.airbagDeployed)
        put("telltales", JSONArray().apply {
            for (state in snapshot.telltales) {
                put(
                    JSONObject().apply {
                        put("telltale", state.telltale.name)
                        put("color", state.color.name)
                        put("blinking", state.blinking)
                    },
                )
            }
        })
        put("unsupported", JSONArray().apply {
            for (item in Item.entries) if (item in snapshot.unsupported) put(item.name)
        })
    }

    private fun decode(profile: Profile, row: JSONObject): StoredInspection? {
        val date = runCatching { LocalDate.parse(row.optString("date")) }.getOrNull() ?: return null
        val snapshot = row.optJSONObject("snapshot") ?: return null
        return StoredInspection(
            profile = enumOrNull<Profile>(row.optString("profile")) ?: profile,
            date = date,
            seq = row.optInt("seq", 1),
            atMillis = row.optLong("atMillis", 0L),
            scenarioId = if (row.isNull("scenarioId")) null else row.optString("scenarioId"),
            snapshot = decodeSnapshot(snapshot),
            distanceDriven = number(row, "distanceDriven"),
        )
    }

    private fun decodeSnapshot(json: JSONObject): VehicleSnapshot = VehicleSnapshot(
        engineOil = enumOrNull<FluidLevel>(json.optString("engineOil")) ?: FluidLevel.NORMAL,
        transmissionOil = enumOrNull<FluidLevel>(json.optString("transmissionOil")) ?: FluidLevel.NORMAL,
        brakeFluid = enumOrNull<FluidLevel>(json.optString("brakeFluid")) ?: FluidLevel.NORMAL,
        lowTireWheels = names(json.optJSONArray("lowTireWheels"))
            .mapNotNull { enumOrNull<WheelArea>(it) }
            .toSet(),
        fuelPercent = number(json, "fuelPercent"),
        batteryPercent = number(json, "batteryPercent"),
        washerFluidLow = json.optBoolean("washerFluidLow"),
        airbagDeployed = json.optBoolean("airbagDeployed"),
        telltales = decodeTelltales(json.optJSONArray("telltales")),
        unsupported = names(json.optJSONArray("unsupported"))
            .mapNotNull { enumOrNull<Item>(it) }
            .toSet(),
    )

    private fun decodeTelltales(array: JSONArray?): List<TelltaleState> {
        if (array == null) return emptyList()
        val out = mutableListOf<TelltaleState>()
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val telltale = enumOrNull<Telltale>(row.optString("telltale")) ?: continue
            val color = enumOrNull<Color>(row.optString("color")) ?: Color.UNKNOWN
            out += TelltaleState(telltale, color, row.optBoolean("blinking"))
        }
        return out
    }

    private fun names(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { array.optString(it, null) }.filter { it.isNotEmpty() }
    }

    private fun number(json: JSONObject, key: String): Double? =
        if (json.isNull(key)) null else json.optDouble(key).takeUnless { it.isNaN() }

    private inline fun <reified T : Enum<T>> enumOrNull(token: String): T? {
        if (token.isEmpty()) return null
        return enumValues<T>().firstOrNull { it.name == token }
    }

    private companion object {
        const val REAL_FILE = "inspections_real.json"
        const val DEMO_FILE = "inspections_demo.json"
        const val RETENTION_DAYS = 90L
        const val SCENARIO_D3 = "D3"
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

/** 오늘부터 거꾸로 점검이 하루도 빠지지 않고 이어진 날 수. 같은 날 여러 번은 하루로 센다. */
fun streakDays(records: List<StoredInspection>, today: LocalDate): Int {
    val days = records.map { it.date }.filter { !it.isAfter(today) }.toSortedSet()
    var streak = 0
    var day = today
    while (day in days) {
        streak++
        day = day.minusDays(1)
    }
    return streak
}

/** 마지막 점검 날의 `M월 d일`. 기록이 없으면 "아직 점검한 적 없어요". */
fun lastInspectedLabel(records: List<StoredInspection>, today: LocalDate): String {
    val last = records.map { it.date }.filter { !it.isAfter(today) }.maxOrNull()
        ?: return InspectionHistory.EMPTY_HOME_LABEL
    return InspectionHistory.label(last)
}
