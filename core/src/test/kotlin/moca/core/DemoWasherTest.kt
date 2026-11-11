package moca.core

// 데모 D7: 워셔액만 부족하다.
// 「휴대폰으로 부품 보기」(QR)는 워셔액 감점에서만 나오는데, D0~D6 에는 그 상태가 없어 데모로 눌러 볼 수 없었다.
class DemoWasherTest {
    private fun d7() = Demo.scenarios().first { it.first.id == "D7" }.first

    @Test
    fun scenariosAreD0ThroughD7() {
        Assert.eq(
            listOf("D0", "D1", "D2", "D3", "D4", "D5", "D6", "D7"),
            Demo.scenarios().map { it.first.id },
        )
    }

    @Test
    fun d7HasOnlyTheWasherDeduction() {
        val deductions = Rules.deductions(Demo.snapshotOf("D7"))
        Assert.eq(1, deductions.size)
        Assert.eq("WASHER", deductions.first().group)
        Assert.eq(Category.SAFETY, deductions.first().category)
        Assert.eq(10, deductions.first().points)
    }

    @Test
    fun d7LeadsToPartsOnThePhone() {
        val snapshot = Demo.snapshotOf("D7")
        val top = Priority.topProblem(snapshot)
        Assert.eq("WASHER", top?.group)
        Assert.eq(FixAction.PARTS, Priority.actionFor(top!!, snapshot))
    }

    @Test
    fun d7OnlySafetyDrops() {
        val evaluation = Demo.evaluateOf("D7")
        Assert.eq(90, evaluation.categories[Category.SAFETY])
        for (category in listOf(Category.POWER, Category.BRAKE_STEERING, Category.TIRE, Category.ELECTRIC)) {
            Assert.eq(100, evaluation.categories[category], category.name)
        }
    }

    @Test
    fun d7ExpectedValuesComeFromTheEngine() {
        val evaluation = Demo.evaluateOf("D7")
        val scenario = d7()
        Assert.eq(evaluation.grade, scenario.expectedGrade)
        Assert.eq(evaluation.displayedOverall, scenario.expectedDisplayed)
        Assert.near(evaluation.overall, scenario.expectedOverall, 0.05)
    }

    @Test
    fun d7OpponentIsASeedNoOtherScenarioUses() {
        val scenario = d7()
        Assert.eq(true, Seeds.byNickname(scenario.opponent) != null, "D7 상대 시드")
        Assert.eq(1, Demo.scenarios().count { it.first.opponent == scenario.opponent })
    }
}
