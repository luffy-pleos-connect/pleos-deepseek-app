package moca.core

// 계획 v9 의 5-2(홈의 「가장 먼저 할 일」)와 D3(문제를 찾은 뒤 이어 주기)의 규칙이다.
// 가장 먼저 할 일 = 감점이 가장 큰 것. 감점이 같으면 카테고리 순서(동력, 제동·조향, 타이어, 전기, 안전)가 앞선 것.
// 이어 줄 곳 = 감점의 종류로 정한다. 워셔액은 부품(휴대폰으로), 잔량 부족은 충전소·주유소, 나머지는 정비소.
class PriorityTest {
    private val absRed = TelltaleState(Telltale.ABS, Color.RED)

    @Test
    fun noDeductionMeansNothingToDo() {
        Assert.isNull(Priority.topProblem(VehicleSnapshot()))
    }

    @Test
    fun biggestDeductionComesFirst() {
        val snapshot = VehicleSnapshot(telltales = listOf(absRed), lowTireWheels = setOf(WheelArea.FL))
        val top = Priority.topProblem(snapshot)
        Assert.eq("TT_ABS", top?.group)
        Assert.eq(50, top?.points)
    }

    @Test
    fun smallerDeductionIsChosenWhenItIsTheOnlyOne() {
        val top = Priority.topProblem(VehicleSnapshot(washerFluidLow = true))
        Assert.eq("WASHER", top?.group)
    }

    @Test
    fun sameDeductionFollowsCategoryOrder() {
        // ABS 빨강(제동·조향 50)과 에어백 전개(안전 50)가 같이 있으면 제동·조향이 먼저다.
        val snapshot = VehicleSnapshot(telltales = listOf(absRed), airbagDeployed = true)
        Assert.eq(Category.BRAKE_STEERING, Priority.topProblem(snapshot)?.category)
    }

    @Test
    fun unreadableItemIsNeverTheTopProblem() {
        // 읽지 못한 항목은 감점이 아니다. 가장 먼저 할 일로 나오면 안 된다.
        val snapshot = VehicleSnapshot(washerFluidLow = true, unsupported = setOf(Item.WASHER_FLUID))
        Assert.isNull(Priority.topProblem(snapshot))
    }

    @Test
    fun warningLightAndFluidGoToRepairShopAndTireGoesToTireShop() {
        val s1 = VehicleSnapshot(telltales = listOf(absRed))
        Assert.eq(FixAction.REPAIR_SHOP, Priority.actionFor(Priority.topProblem(s1)!!, s1))
        val s2 = VehicleSnapshot(engineOil = FluidLevel.LOW)
        Assert.eq(FixAction.REPAIR_SHOP, Priority.actionFor(Priority.topProblem(s2)!!, s2))
        val s3 = VehicleSnapshot(lowTireWheels = setOf(WheelArea.RR))
        Assert.eq(FixAction.TIRE_SHOP, Priority.actionFor(Priority.topProblem(s3)!!, s3))
    }

    @Test
    fun washerGoesToPartsOnPhone() {
        val s = VehicleSnapshot(washerFluidLow = true)
        Assert.eq(FixAction.PARTS, Priority.actionFor(Priority.topProblem(s)!!, s))
    }

    @Test
    fun lowBatteryGoesToChargerAndLowFuelGoesToGasStation() {
        val battery = VehicleSnapshot(batteryPercent = 5.0, fuelPercent = null)
        Assert.eq(FixAction.CHARGE, Priority.actionFor(Priority.topProblem(battery)!!, battery))
        val fuel = VehicleSnapshot(batteryPercent = null, fuelPercent = 5.0)
        Assert.eq(FixAction.FUEL, Priority.actionFor(Priority.topProblem(fuel)!!, fuel))
    }

    @Test
    fun everyActionHasButtonTextAndOnlyPartsHasNoSearchWord() {
        for (action in FixAction.entries) {
            Assert.isTrue(action.label.isNotBlank(), "label of $action")
            Assert.eq(action == FixAction.PARTS, action.searchKeyword == null, "searchKeyword of $action")
        }
        Assert.eq("정비소", FixAction.REPAIR_SHOP.searchKeyword)
        Assert.eq("전기차 충전소", FixAction.CHARGE.searchKeyword)
        Assert.eq("주유소", FixAction.FUEL.searchKeyword)
    }
}
