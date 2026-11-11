package moca.core

import java.time.LocalDate

/**
 * 점검 한 줄을 텍스트로 넣고 뺀다. 화면과 SharedPreferences 는 이 문자열만 저장한다.
 * 날짜 문구는 `M월 d일` 이다.
 */
object InspectionHistory {
    fun line(date: LocalDate, snapshot: VehicleSnapshot): String {
        val wheels = names(snapshot.lowTireWheels.map { it.name })
        val unsupported = names(snapshot.unsupported.map { it.name })
        val telltales = if (snapshot.telltales.isEmpty()) {
            "-"
        } else {
            snapshot.telltales.joinToString("+") {
                "${it.telltale.name}:${it.color.name}:${if (it.blinking) 1 else 0}"
            }
        }
        return listOf(
            date.toString(),
            snapshot.engineOil.name,
            snapshot.transmissionOil.name,
            snapshot.brakeFluid.name,
            wheels,
            num(snapshot.fuelPercent),
            num(snapshot.batteryPercent),
            if (snapshot.washerFluidLow) "1" else "0",
            if (snapshot.airbagDeployed) "1" else "0",
            unsupported,
            telltales,
        ).joinToString(";")
    }

    fun read(text: String, profile: Profile): List<Inspection> {
        val out = mutableListOf<Inspection>()
        for (raw in text.lines()) {
            val parsed = decode(raw.trim()) ?: continue
            out += Inspection(profile, parsed.first, parsed.second, seq = out.size.toLong())
        }
        return out
    }

    const val EMPTY_HOME_LABEL = "아직 점검한 적이 없어요"

    fun latestLabel(text: String): String? {
        val dates = read(text, Profile.REAL).map { it.inspectedAt }
        val last = dates.maxOrNull() ?: return null
        return label(last)
    }

    fun homeLabel(text: String): String = latestLabel(text) ?: EMPTY_HOME_LABEL

    /** 오늘보다 뒤의 날짜는 기기 시계가 바뀐 기록이므로 뺀다. Care.evaluate 와 같은 기준이다. */
    fun latestLabel(text: String, today: LocalDate): String? {
        val dates = read(text, Profile.REAL).map { it.inspectedAt }.filter { !it.isAfter(today) }
        val last = dates.maxOrNull() ?: return null
        return label(last)
    }

    fun homeLabel(text: String, today: LocalDate): String = latestLabel(text, today) ?: EMPTY_HOME_LABEL

    fun careScore(text: String, today: LocalDate): Int =
        Care.evaluate(read(text, Profile.REAL), Profile.REAL, today).score

    fun trendRows(text: String, profile: Profile): List<String> {
        val records = read(text, profile)
        return records.mapIndexed { index, inspection ->
            val care = Care.evaluate(records.take(index + 1), profile, inspection.inspectedAt).score
            val shown = Scoring.evaluate(inspection.snapshot, care).displayedOverall?.toString() ?: "—"
            "${label(inspection.inspectedAt)} $shown"
        }
    }

    fun label(date: LocalDate): String = "${date.monthValue}월 ${date.dayOfMonth}일"

    private fun decode(line: String): Pair<LocalDate, VehicleSnapshot>? {
        if (line.isEmpty()) return null
        val parts = line.split(";")
        if (parts.size != 11) return null
        val date = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return null
        val engine = enumOrNull<FluidLevel>(parts[1]) ?: return null
        val transmission = enumOrNull<FluidLevel>(parts[2]) ?: return null
        val brake = enumOrNull<FluidLevel>(parts[3]) ?: return null
        val wheels = parts[4].split("+").mapNotNull { enumOrNull<WheelArea>(it) }.toSet()
        if (parts[4] != "-" && wheels.isEmpty()) return null
        val fuel = parseNum(parts[5]) ?: return null
        val battery = parseNum(parts[6]) ?: return null
        val washer = when (parts[7]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val airbag = when (parts[8]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val unsupported = parts[9].split("+").mapNotNull { enumOrNull<Item>(it) }.toSet()
        if (parts[9] != "-" && unsupported.isEmpty()) return null
        val telltales = if (parts[10] == "-") {
            emptyList()
        } else {
            parts[10].split("+").map { token ->
                val bits = token.split(":")
                if (bits.size != 3) return null
                val telltale = enumOrNull<Telltale>(bits[0]) ?: return null
                val color = enumOrNull<Color>(bits[1]) ?: return null
                val blinking = when (bits[2]) {
                    "1" -> true
                    "0" -> false
                    else -> return null
                }
                TelltaleState(telltale, color, blinking)
            }
        }
        return date to VehicleSnapshot(
            engineOil = engine,
            transmissionOil = transmission,
            brakeFluid = brake,
            lowTireWheels = wheels,
            fuelPercent = fuel.value,
            batteryPercent = battery.value,
            washerFluidLow = washer,
            airbagDeployed = airbag,
            telltales = telltales,
            unsupported = unsupported,
        )
    }

    private fun names(values: List<String>): String =
        if (values.isEmpty()) "-" else values.sorted().joinToString("+")

    private fun num(value: Double?): String = value?.toString() ?: "-"

    private class ParsedNum(val value: Double?)

    /** `-` 는 값 없음. 그 외에는 숫자만 받는다. 실패하면 null. */
    private fun parseNum(token: String): ParsedNum? {
        if (token == "-") return ParsedNum(null)
        val number = token.toDoubleOrNull() ?: return null
        return ParsedNum(number)
    }

    private inline fun <reified T : Enum<T>> enumOrNull(token: String): T? {
        if (token == "-" || token.isEmpty()) return null
        return enumValues<T>().firstOrNull { it.name == token }
    }
}
