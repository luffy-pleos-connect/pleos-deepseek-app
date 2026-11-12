package moca.core

// 계획 v10 3.2 의 8·9·21 번. lead 가 쓴 테스트다. 워커는 고치지 않는다.
class CoreV10Test {
    private fun eval(id: String) = Scoring.evaluate(Demo.snapshotOf(id), Demo.CARE_TODAY)

    @Test
    fun tireProblemLeadsToTireShop() {
        val s = Demo.snapshotOf("D3")
        val tire = Rules.deductions(s).first { it.category == Category.TIRE }
        Assert.eq(FixAction.TIRE_SHOP, Priority.actionFor(tire, s))
        Assert.eq("가까운 타이어점 안내", FixAction.TIRE_SHOP.label)
        Assert.eq("타이어", FixAction.TIRE_SHOP.searchKeyword)
        // 가장 큰 문제(ABS)는 그대로 정비소다
        Assert.eq(FixAction.REPAIR_SHOP, Priority.actionFor(Priority.topProblem(s)!!, s))
    }

    @Test
    fun lowBatterySaysTheActualPercent() {
        val s = Demo.snapshotOf("D4")
        val top = Priority.topProblem(s)!!
        Assert.eq("배터리 9%", top.label)
        Assert.eq(FixAction.CHARGE, Priority.actionFor(top, s))
    }

    @Test
    fun howToRaiseStartsWithTheTopProblem() {
        val s = Demo.snapshotOf("D3")
        Assert.eq("「ABS 경고등」부터 해결해 보세요", GradeHow.line(eval("D3"), Priority.topProblem(s)))
    }

    @Test
    fun howToRaiseWithoutProblemsIsAboutCare() {
        val s = Demo.snapshotOf("D0")
        Assert.eq(null, Priority.topProblem(s))
        Assert.eq("점검을 매일 이어 가면 관리 점수가 올라요", GradeHow.line(eval("D0"), null))
    }

    @Test
    fun noLineWhenThereIsNoNextGrade() {
        val top = eval("D0").copy(nextGrade = null, pointsToNext = null)
        Assert.eq(null, GradeHow.line(top, null))
    }

    @Test
    fun rankLineSaysWhetherRealUsersAreCounted() {
        Assert.eq("오늘 9위 · 13명 중 · 연습 순위", RankText.line(9, 13, 0))
        Assert.eq("오늘 10위 · 14명 중 · 실제 사용자 1명 포함", RankText.line(10, 14, 1))
    }
}
