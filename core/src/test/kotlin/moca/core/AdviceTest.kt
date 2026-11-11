package moca.core

// 상세 화면의 조치 한 줄. 종류마다 문구가 있고, 경고등 색은 점수가 아니라 스냅샷으로 가른다.
class AdviceTest {
    @Test
    fun eachKindHasItsOwnSentence() {
        Assert.eq(
            "운행을 멈추고 가까운 정비소에서 점검받으세요",
            adviceOf(VehicleSnapshot(telltales = listOf(TelltaleState(Telltale.ABS, Color.RED)))),
        )
        Assert.eq(
            "가까운 시일 안에 정비소에서 점검받으세요",
            adviceOf(VehicleSnapshot(telltales = listOf(TelltaleState(Telltale.ABS, Color.YELLOW)))),
        )
        Assert.eq(
            "정비소에서 보충하거나 교환하세요",
            adviceOf(VehicleSnapshot(engineOil = FluidLevel.LOW)),
        )
        Assert.eq(
            "정비소에서 보충하거나 교환하세요",
            adviceOf(VehicleSnapshot(brakeFluid = FluidLevel.CRITICALLY_LOW)),
        )
        Assert.eq(
            "공기압을 보충하세요. 계속 낮으면 정비소에서 확인하세요",
            adviceOf(VehicleSnapshot(lowTireWheels = setOf(WheelArea.FL))),
        )
        Assert.eq(
            "워셔액을 보충하세요",
            adviceOf(VehicleSnapshot(washerFluidLow = true)),
        )
        Assert.eq(
            "가까운 충전소나 주유소에 들르세요",
            adviceOf(VehicleSnapshot(fuelPercent = 5.0, batteryPercent = null)),
        )
        Assert.eq(
            "정비소에서 에어백 계통을 점검받으세요",
            adviceOf(VehicleSnapshot(airbagDeployed = true)),
        )
    }

    @Test
    fun redAndYellowSentencesDiffer() {
        val red = adviceOf(VehicleSnapshot(telltales = listOf(TelltaleState(Telltale.ABS, Color.RED))))
        val yellow = adviceOf(VehicleSnapshot(telltales = listOf(TelltaleState(Telltale.ESC, Color.YELLOW))))
        Assert.isTrue(red.isNotBlank())
        Assert.isTrue(yellow.isNotBlank())
        Assert.ne(red, yellow)
    }

    @Test
    fun warningColorComesFromTheSnapshotNotThePoints() {
        val yellow = VehicleSnapshot(telltales = listOf(TelltaleState(Telltale.ABS, Color.YELLOW)))
        val pointsLookRed = Deduction("TT_ABS", Category.BRAKE_STEERING, Rules.RED_PENALTY, "ABS 경고등")
        Assert.eq("가까운 시일 안에 정비소에서 점검받으세요", Priority.advice(pointsLookRed, yellow))

        val red = VehicleSnapshot(telltales = listOf(TelltaleState(Telltale.ABS, Color.RED)))
        val pointsLookYellow = Deduction("TT_ABS", Category.BRAKE_STEERING, Rules.YELLOW_PENALTY, "ABS 경고등")
        Assert.eq("운행을 멈추고 가까운 정비소에서 점검받으세요", Priority.advice(pointsLookYellow, red))

        val criticalOil = VehicleSnapshot(engineOil = FluidLevel.CRITICALLY_LOW)
        val oil = Rules.deductions(criticalOil).single()
        Assert.eq(Rules.FLUID_CRITICALLY_LOW, oil.points)
        Assert.eq("정비소에서 보충하거나 교환하세요", Priority.advice(oil, criticalOil))

        val highFluid = VehicleSnapshot(brakeFluid = FluidLevel.HIGH)
        val high = Rules.deductions(highFluid).single()
        Assert.eq(Rules.FLUID_HIGH, high.points)
        Assert.eq("정비소에서 보충하거나 교환하세요", Priority.advice(high, highFluid))
    }

    @Test
    fun everyGroupRulesCanDeductHasAdvice() {
        val snapshots = mutableListOf<VehicleSnapshot>()
        for (telltale in Telltale.entries) {
            if (Rules.categoryOf(telltale) == null) continue
            snapshots += VehicleSnapshot(telltales = listOf(TelltaleState(telltale, Color.RED)))
            snapshots += VehicleSnapshot(telltales = listOf(TelltaleState(telltale, Color.YELLOW)))
        }
        snapshots += VehicleSnapshot(engineOil = FluidLevel.LOW)
        snapshots += VehicleSnapshot(engineOil = FluidLevel.HIGH)
        snapshots += VehicleSnapshot(transmissionOil = FluidLevel.CRITICALLY_LOW)
        snapshots += VehicleSnapshot(brakeFluid = FluidLevel.LOW)
        snapshots += VehicleSnapshot(lowTireWheels = setOf(WheelArea.RR))
        snapshots += VehicleSnapshot(lowTireWheels = setOf(WheelArea.FL, WheelArea.FR, WheelArea.RL))
        snapshots += VehicleSnapshot(fuelPercent = 1.0, batteryPercent = 90.0)
        snapshots += VehicleSnapshot(fuelPercent = null, batteryPercent = 1.0)
        snapshots += VehicleSnapshot(washerFluidLow = true)
        snapshots += VehicleSnapshot(airbagDeployed = true)

        val seen = mutableSetOf<String>()
        for (snapshot in snapshots) {
            for (deduction in Rules.deductions(snapshot)) {
                seen += deduction.group
                val text = Priority.advice(deduction, snapshot)
                Assert.isTrue(text.isNotBlank(), deduction.group)
                Assert.isFalse(text.contains("고장입니다"), deduction.group)
            }
        }

        val expected = buildSet {
            for (telltale in Telltale.entries) {
                if (Rules.categoryOf(telltale) != null) add(Rules.groupOf(telltale))
            }
            add("ENGINE_OIL")
            add("TRANSMISSION_OIL")
            add("BRAKE_FLUID")
            add("TIRE_PRESSURE")
            add("ENERGY")
            add("WASHER")
            add("AIRBAG")
        }
        Assert.eq(expected, seen)
    }

    private fun adviceOf(snapshot: VehicleSnapshot): String {
        val deduction = Rules.deductions(snapshot).single()
        return Priority.advice(deduction, snapshot)
    }
}
