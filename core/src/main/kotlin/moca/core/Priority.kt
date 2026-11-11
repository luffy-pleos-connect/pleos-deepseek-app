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
}
