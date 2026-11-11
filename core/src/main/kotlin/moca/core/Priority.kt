package moca.core

enum class FixAction(val label: String, val searchKeyword: String?) {
    REPAIR_SHOP("가까운 정비소 안내", "정비소"),
    CHARGE("가까운 충전소 안내", "전기차 충전소"),
    FUEL("가까운 주유소 안내", "주유소"),
    PARTS("휴대폰으로 부품 보기", null),
}

object Priority {
    private const val LOW_BATTERY_PERCENT = 10.0

    fun topProblem(snapshot: VehicleSnapshot): Deduction? =
        Rules.deductions(snapshot).minWithOrNull(
            compareByDescending<Deduction> { it.points }
                .thenBy { it.category.ordinal }
                .thenBy { it.group },
        )

    fun actionFor(deduction: Deduction, snapshot: VehicleSnapshot): FixAction = when (deduction.group) {
        "WASHER" -> FixAction.PARTS
        "ENERGY" -> {
            val battery = snapshot.batteryPercent
            if (battery != null && battery < LOW_BATTERY_PERCENT) FixAction.CHARGE else FixAction.FUEL
        }
        else -> FixAction.REPAIR_SHOP
    }

    /**
     * 감점 종류별 조치 한 줄.
     * 경고등 문구는 점수(50/25)가 아니라 스냅샷에 찍힌 색으로 가른다.
     */
    fun advice(deduction: Deduction, snapshot: VehicleSnapshot): String {
        when (warningColor(deduction, snapshot)) {
            Color.RED -> return "운행을 멈추고 가까운 정비소에서 점검받으세요"
            Color.YELLOW -> return "가까운 시일 안에 정비소에서 점검받으세요"
            else -> Unit
        }
        return when (deduction.group) {
            "ENGINE_OIL", "TRANSMISSION_OIL", "BRAKE_FLUID" -> "정비소에서 보충하거나 교환하세요"
            "TIRE_PRESSURE" -> "공기압을 보충하세요. 계속 낮으면 정비소에서 확인하세요"
            "WASHER" -> "워셔액을 보충하세요"
            "ENERGY" -> "가까운 충전소나 주유소에 들르세요"
            "AIRBAG" -> "정비소에서 에어백 계통을 점검받으세요"
            else -> "가까운 시일 안에 정비소에서 점검받으세요"
        }
    }

    private fun warningColor(deduction: Deduction, snapshot: VehicleSnapshot): Color? {
        if (Item.TELLTALES in snapshot.unsupported) return null
        val matched = snapshot.telltales.filter { state ->
            state.active &&
                Rules.groupOf(state.telltale) == deduction.group &&
                deduction.label == "${state.telltale.label} 경고등"
        }
        return when {
            matched.any { it.color == Color.RED } -> Color.RED
            matched.any { it.color == Color.YELLOW } -> Color.YELLOW
            else -> null
        }
    }
}
