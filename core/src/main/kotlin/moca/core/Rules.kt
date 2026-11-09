package moca.core

data class Deduction(
    val group: String,
    val category: Category,
    val points: Int,
    val label: String,
)

object Rules {
    const val RED_PENALTY = 50
    const val YELLOW_PENALTY = 25
    const val FLUID_LOW = 40
    const val FLUID_CRITICALLY_LOW = 50
    const val FLUID_HIGH = 25
    const val TIRE_PER_WHEEL = 20
    const val ENERGY_LOW = 10
    const val WASHER_LOW = 10
    const val AIRBAG_DEPLOYED = 50

    fun categoryOf(telltale: Telltale): Category? = when (telltale) {
        Telltale.ENGINE,
        Telltale.ENGINE_OIL_PRESSURE,
        Telltale.ENGINE_TEMPERATURE,
        Telltale.EV_SYSTEM,
        Telltale.HEV_SYSTEM,
        Telltale.PARTICLE_FILTER -> Category.POWER

        Telltale.ABS,
        Telltale.ESC,
        Telltale.MDPS -> Category.BRAKE_STEERING

        Telltale.TPMS -> Category.TIRE

        Telltale.LOW_VOLT_BATTERY,
        Telltale.POWER_DOWN -> Category.ELECTRIC

        Telltale.AIRBAG,
        Telltale.MASTER_WARNING -> Category.SAFETY

        Telltale.LOW_FUEL,
        Telltale.LOW_EV_BATTERY,
        Telltale.SEATBELT,
        Telltale.PASSENGER_AIRBAG_OFF -> null
    }

    fun groupOf(telltale: Telltale): String = when (telltale) {
        Telltale.TPMS -> "TIRE_PRESSURE"
        Telltale.AIRBAG -> "AIRBAG"
        Telltale.LOW_VOLT_BATTERY, Telltale.POWER_DOWN -> "ELECTRIC_12V"
        else -> "TT_${telltale.name}"
    }

    fun measured(snapshot: VehicleSnapshot, category: Category): Boolean = when (category) {
        Category.POWER ->
            (Item.ENGINE_OIL !in snapshot.unsupported && snapshot.engineOil != FluidLevel.ERROR) ||
                (Item.TRANSMISSION_OIL !in snapshot.unsupported && snapshot.transmissionOil != FluidLevel.ERROR) ||
                hasActiveTelltale(snapshot, category)

        Category.BRAKE_STEERING ->
            (Item.BRAKE_FLUID !in snapshot.unsupported && snapshot.brakeFluid != FluidLevel.ERROR) ||
                hasActiveTelltale(snapshot, category)

        Category.TIRE -> Item.TIRE !in snapshot.unsupported

        Category.ELECTRIC ->
            (Item.ENERGY !in snapshot.unsupported &&
                (snapshot.fuelPercent != null || snapshot.batteryPercent != null)) ||
                hasActiveTelltale(snapshot, category)

        Category.SAFETY ->
            Item.WASHER_FLUID !in snapshot.unsupported ||
                Item.AIRBAG !in snapshot.unsupported ||
                hasActiveTelltale(snapshot, category)
    }

    private fun hasActiveTelltale(snapshot: VehicleSnapshot, category: Category): Boolean {
        if (Item.TELLTALES in snapshot.unsupported) return false
        return snapshot.telltales.any { state ->
            state.active && categoryOf(state.telltale) == category && telltaleAvailable(snapshot, state.telltale)
        }
    }

    private fun telltaleAvailable(snapshot: VehicleSnapshot, telltale: Telltale): Boolean =
        !(telltale == Telltale.TPMS && Item.TIRE in snapshot.unsupported)

    fun hasRed(snapshot: VehicleSnapshot): Boolean {
        if (Item.TELLTALES in snapshot.unsupported) return false
        return snapshot.telltales.any { state ->
            state.active && state.color == Color.RED &&
                categoryOf(state.telltale) != null &&
                telltaleAvailable(snapshot, state.telltale)
        }
    }

    fun deductions(snapshot: VehicleSnapshot): List<Deduction> {
        val candidates = mutableListOf<Deduction>()

        if (Item.TELLTALES !in snapshot.unsupported) {
            for (state in snapshot.telltales) {
                if (!state.active) continue
                if (!telltaleAvailable(snapshot, state.telltale)) continue
                val category = categoryOf(state.telltale) ?: continue
                val points = when (state.color) {
                    Color.RED -> RED_PENALTY
                    Color.YELLOW -> YELLOW_PENALTY
                    else -> 0
                }
                if (points > 0) {
                    candidates += Deduction(
                        groupOf(state.telltale),
                        category,
                        points,
                        "${state.telltale.label} 경고등",
                    )
                }
            }
        }

        if (Item.ENGINE_OIL !in snapshot.unsupported) {
            fluid(snapshot.engineOil, Category.POWER, "ENGINE_OIL", "엔진오일")?.let { candidates += it }
        }
        if (Item.TRANSMISSION_OIL !in snapshot.unsupported) {
            fluid(snapshot.transmissionOil, Category.POWER, "TRANSMISSION_OIL", "변속기오일")?.let { candidates += it }
        }
        if (Item.BRAKE_FLUID !in snapshot.unsupported) {
            fluid(snapshot.brakeFluid, Category.BRAKE_STEERING, "BRAKE_FLUID", "브레이크액")?.let { candidates += it }
        }
        if (Item.TIRE !in snapshot.unsupported && snapshot.lowTireWheels.isNotEmpty()) {
            candidates += Deduction(
                "TIRE_PRESSURE",
                Category.TIRE,
                TIRE_PER_WHEEL * snapshot.lowTireWheels.size,
                "타이어 저압 ${snapshot.lowTireWheels.size}개",
            )
        }
        if (Item.ENERGY !in snapshot.unsupported && energyLow(snapshot)) {
            candidates += Deduction("ENERGY", Category.ELECTRIC, ENERGY_LOW, "연료·배터리 잔량 10% 미만")
        }
        if (Item.WASHER_FLUID !in snapshot.unsupported && snapshot.washerFluidLow) {
            candidates += Deduction("WASHER", Category.SAFETY, WASHER_LOW, "워셔액 부족")
        }
        if (Item.AIRBAG !in snapshot.unsupported && snapshot.airbagDeployed) {
            candidates += Deduction("AIRBAG", Category.SAFETY, AIRBAG_DEPLOYED, "에어백 전개")
        }

        return candidates
            .groupBy { it.group }
            .map { (_, group) -> group.maxBy { it.points } }
            .sortedWith(compareBy({ it.category.ordinal }, { it.group }))
    }

    fun groupKeys(snapshot: VehicleSnapshot): Set<String> = deductions(snapshot).map { it.group }.toSet()

    private fun energyLow(snapshot: VehicleSnapshot): Boolean =
        (snapshot.fuelPercent != null && snapshot.fuelPercent < 10.0) ||
            (snapshot.batteryPercent != null && snapshot.batteryPercent < 10.0)

    private fun fluid(level: FluidLevel, category: Category, group: String, name: String): Deduction? =
        when (level) {
            FluidLevel.LOW -> Deduction(group, category, FLUID_LOW, "$name 부족")
            FluidLevel.CRITICALLY_LOW -> Deduction(group, category, FLUID_CRITICALLY_LOW, "$name 심각 부족")
            FluidLevel.HIGH -> Deduction(group, category, FLUID_HIGH, "$name 과다")
            FluidLevel.NORMAL, FluidLevel.ERROR -> null
        }
}
