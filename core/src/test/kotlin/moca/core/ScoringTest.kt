package moca.core

class ScoringTest {

    private fun normal() = VehicleSnapshot.normal()

    private fun withTelltales(vararg states: TelltaleState) = normal().copy(telltales = states.toList())

    @Test
    fun normalSnapshotIsMeasuredAndPerfect() {
        val cats = Scoring.categories(normal())
        for (category in Category.values()) {
            Assert.eq(100, cats[category], "$category")
        }
    }

    @Test
    fun normalHealthIs100() {
        Assert.near(100.0, Scoring.health(Scoring.categories(normal())))
    }

    @Test
    fun redAbsDropsBrakeTo50() {
        val s = withTelltales(Fx.red(Telltale.ABS))
        Assert.eq(50, Fx.cat(s, Category.BRAKE_STEERING))
        Assert.eq(100, Fx.cat(s, Category.POWER))
    }

    @Test
    fun redEngineTelltaleDropsPowerTo50() {
        val s = withTelltales(Fx.red(Telltale.ENGINE))
        Assert.eq(50, Fx.cat(s, Category.POWER))
    }

    @Test
    fun yellowEngineTelltaleDropsPowerTo75() {
        val s = withTelltales(Fx.yellow(Telltale.ENGINE))
        Assert.eq(75, Fx.cat(s, Category.POWER))
    }

    @Test
    fun redEscAndMdpsEachCost50() {
        Assert.eq(50, Fx.cat(withTelltales(Fx.red(Telltale.ESC)), Category.BRAKE_STEERING))
        Assert.eq(50, Fx.cat(withTelltales(Fx.red(Telltale.MDPS)), Category.BRAKE_STEERING))
    }

    @Test
    fun differentGroupsInSameCategoryAddUp() {
        val s = withTelltales(Fx.red(Telltale.ABS), Fx.red(Telltale.ESC))
        Assert.eq(0, Fx.cat(s, Category.BRAKE_STEERING))
    }

    @Test
    fun redAndYellowInDifferentGroupsStack() {
        val s = withTelltales(Fx.red(Telltale.ABS), Fx.yellow(Telltale.MDPS))
        Assert.eq(25, Fx.cat(s, Category.BRAKE_STEERING))
    }

    @Test
    fun engineOilLowCosts40() {
        Assert.eq(60, Fx.cat(normal().copy(engineOil = FluidLevel.LOW), Category.POWER))
    }

    @Test
    fun engineOilCriticallyLowCosts50() {
        Assert.eq(50, Fx.cat(normal().copy(engineOil = FluidLevel.CRITICALLY_LOW), Category.POWER))
    }

    @Test
    fun engineOilHighCosts25() {
        Assert.eq(75, Fx.cat(normal().copy(engineOil = FluidLevel.HIGH), Category.POWER))
    }

    @Test
    fun engineOilErrorIsNotMeasured() {
        val s = normal().copy(engineOil = FluidLevel.ERROR)
        Assert.eq(100, Fx.cat(s, Category.POWER))
    }

    @Test
    fun engineOilErrorStillLetsTelltaleCount() {
        val s = normal().copy(engineOil = FluidLevel.ERROR, telltales = listOf(Fx.red(Telltale.ENGINE_OIL_PRESSURE)))
        Assert.eq(50, Fx.cat(s, Category.POWER))
    }

    @Test
    fun transmissionOilLowCosts40() {
        Assert.eq(60, Fx.cat(normal().copy(transmissionOil = FluidLevel.LOW), Category.POWER))
    }

    @Test
    fun engineAndTransmissionOilAreSeparateGroups() {
        val s = normal().copy(engineOil = FluidLevel.LOW, transmissionOil = FluidLevel.LOW)
        Assert.eq(20, Fx.cat(s, Category.POWER))
    }

    @Test
    fun brakeFluidLowCosts40() {
        Assert.eq(60, Fx.cat(normal().copy(brakeFluid = FluidLevel.LOW), Category.BRAKE_STEERING))
    }

    @Test
    fun brakeFluidCriticallyLowCosts50() {
        Assert.eq(50, Fx.cat(normal().copy(brakeFluid = FluidLevel.CRITICALLY_LOW), Category.BRAKE_STEERING))
    }

    @Test
    fun brakeFluidHighCosts25() {
        Assert.eq(75, Fx.cat(normal().copy(brakeFluid = FluidLevel.HIGH), Category.BRAKE_STEERING))
    }

    @Test
    fun oneLowTireCosts20() {
        val s = normal().copy(lowTireWheels = setOf(WheelArea.FL))
        Assert.eq(80, Fx.cat(s, Category.TIRE))
    }

    @Test
    fun twoLowTiresCost40() {
        val s = normal().copy(lowTireWheels = setOf(WheelArea.FL, WheelArea.RR))
        Assert.eq(60, Fx.cat(s, Category.TIRE))
    }

    @Test
    fun fourLowTiresCost80() {
        val s = normal().copy(lowTireWheels = WheelArea.values().toSet())
        Assert.eq(20, Fx.cat(s, Category.TIRE))
    }

    @Test
    fun redTpmsAndFourLowTiresUseLargestSingleDeduction() {
        val s = normal().copy(lowTireWheels = WheelArea.values().toSet()).copy(telltales = listOf(Fx.red(Telltale.TPMS)))
        Assert.eq(20, Fx.cat(s, Category.TIRE))
    }

    @Test
    fun yellowTpmsWithTwoWheelsUsesLargestSingleDeduction() {
        val s = normal().copy(lowTireWheels = setOf(WheelArea.FL, WheelArea.FR)).copy(telltales = listOf(Fx.yellow(Telltale.TPMS)))
        Assert.eq(60, Fx.cat(s, Category.TIRE))
    }

    @Test
    fun yellowTpmsWithOneWheelUses25() {
        val s = normal().copy(lowTireWheels = setOf(WheelArea.FL)).copy(telltales = listOf(Fx.yellow(Telltale.TPMS)))
        Assert.eq(75, Fx.cat(s, Category.TIRE))
    }

    @Test
    fun redTpmsAloneCosts50() {
        Assert.eq(50, Fx.cat(withTelltales(Fx.red(Telltale.TPMS)), Category.TIRE))
    }

    @Test
    fun batteryBelow10Costs10() {
        Assert.eq(90, Fx.cat(normal().copy(batteryPercent = 9.0), Category.ELECTRIC))
    }

    @Test
    fun fuelBelow10Costs10() {
        Assert.eq(90, Fx.cat(normal().copy(fuelPercent = 9.0), Category.ELECTRIC))
    }

    @Test
    fun fuelAndBatteryAreOneGroup() {
        Assert.eq(90, Fx.cat(normal().copy(fuelPercent = 5.0, batteryPercent = 5.0), Category.ELECTRIC))
    }

    @Test
    fun exactly10PercentDoesNotDeduct() {
        Assert.eq(100, Fx.cat(normal().copy(fuelPercent = 10.0, batteryPercent = 10.0), Category.ELECTRIC))
    }

    @Test
    fun washerFluidLowCosts10() {
        Assert.eq(90, Fx.cat(normal().copy(washerFluidLow = true), Category.SAFETY))
    }

    @Test
    fun airbagDeployedCosts50() {
        Assert.eq(50, Fx.cat(normal().copy(airbagDeployed = true), Category.SAFETY))
    }

    @Test
    fun airbagDeployedAndAirbagTelltaleAreOneGroup() {
        val s = normal().copy(airbagDeployed = true, telltales = listOf(Fx.red(Telltale.AIRBAG)))
        Assert.eq(50, Fx.cat(s, Category.SAFETY))
    }

    @Test
    fun masterWarningAndWasherStack() {
        val s = normal().copy(washerFluidLow = true, telltales = listOf(Fx.red(Telltale.MASTER_WARNING)))
        Assert.eq(40, Fx.cat(s, Category.SAFETY))
    }

    @Test
    fun nonWhitelistedTelltalesDoNotDeduct() {
        val s = withTelltales(
            Fx.yellow(Telltale.LOW_FUEL),
            Fx.yellow(Telltale.LOW_EV_BATTERY),
            Fx.red(Telltale.SEATBELT),
            Fx.yellow(Telltale.PASSENGER_AIRBAG_OFF),
        )
        for (category in Category.values()) Assert.eq(100, Fx.cat(s, category), "$category")
    }

    @Test
    fun lowVoltAndPowerDownShareOneGroup() {
        val s = withTelltales(Fx.red(Telltale.LOW_VOLT_BATTERY), Fx.yellow(Telltale.POWER_DOWN))
        Assert.eq(50, Fx.cat(s, Category.ELECTRIC))
    }

    @Test
    fun lowVoltRedAloneCosts50() {
        Assert.eq(50, Fx.cat(withTelltales(Fx.red(Telltale.LOW_VOLT_BATTERY)), Category.ELECTRIC))
    }

    @Test
    fun tireUnsupportedMakesTireNull() {
        val s = normal().copy(unsupported = setOf(Item.TIRE))
        Assert.isNull(Fx.cat(s, Category.TIRE))
        Assert.eq(100, Fx.cat(s, Category.POWER))
    }

    @Test
    fun allUnsupportedMakesEveryCategoryNull() {
        val s = normal().copy(unsupported = Item.values().toSet())
        val cats = Scoring.categories(s)
        for (category in Category.values()) Assert.isNull(cats[category], "$category")
        Assert.isNull(Scoring.health(cats))
    }

    @Test
    fun categoryScoreNeverGoesBelowZero() {
        val s = withTelltales(Fx.red(Telltale.ABS), Fx.red(Telltale.ESC), Fx.red(Telltale.MDPS))
        Assert.eq(0, Fx.cat(s, Category.BRAKE_STEERING))
    }

    @Test
    fun healthAveragesOnlyMeasuredCategories() {
        val s = normal()
            .copy(telltales = listOf(Fx.red(Telltale.ABS)))
            .copy(lowTireWheels = setOf(WheelArea.FL))
        val health = Scoring.health(Scoring.categories(s))
        Assert.near(86.0, health!!)
    }

    @Test
    fun unmeasuredCategoryIsLeftOutOfHealth() {
        val s = normal()
            .copy(telltales = listOf(Fx.red(Telltale.ABS)))
            .copy(unsupported = setOf(Item.TIRE))
        val health = Scoring.health(Scoring.categories(s))
        Assert.near(87.5, health!!)
    }

    @Test
    fun healthIsNullWhenEverythingUnsupported() {
        val s = normal().copy(unsupported = Item.values().toSet())
        Assert.isNull(Scoring.health(Scoring.categories(s)))
    }

    @Test
    fun unsupportedEngineOilStillCountsTelltale() {
        val s = normal().copy(unsupported = setOf(Item.ENGINE_OIL), telltales = listOf(Fx.red(Telltale.ENGINE_OIL_PRESSURE)))
        Assert.eq(50, Fx.cat(s, Category.POWER))
    }

    @Test
    fun powerIsNullWhenBothOilsUnsupportedAndNoTelltale() {
        val s = normal().copy(unsupported = setOf(Item.ENGINE_OIL, Item.TRANSMISSION_OIL))
        Assert.isNull(Fx.cat(s, Category.POWER))
    }

    @Test
    fun redCapDetectedOnlyForWhitelistedRed() {
        Assert.isTrue(Rules.hasRed(withTelltales(Fx.red(Telltale.ABS))))
        Assert.isFalse(Rules.hasRed(withTelltales(Fx.yellow(Telltale.ABS))))
        Assert.isFalse(Rules.hasRed(withTelltales(Fx.red(Telltale.LOW_FUEL))))
    }

    @Test
    fun deductionsAreReportedPerGroup() {
        val s = normal()
            .copy(engineOil = FluidLevel.LOW)
            .copy(telltales = listOf(Fx.red(Telltale.ABS)))
            .copy(lowTireWheels = setOf(WheelArea.FL, WheelArea.FR))
            .copy(washerFluidLow = true)
        val deductions = Rules.deductions(s)
        Assert.eq(4, deductions.size)
        Assert.eq(setOf(40, 50, 40, 10), deductions.map { it.points }.toSet())
    }
}
