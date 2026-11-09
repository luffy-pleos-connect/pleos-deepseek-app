package moca.core

class GradeTest {

    private fun gradeOf(overall: Int, health: Int = 100, red: Boolean = false): Grade? =
        Scoring.gradeFor(overall.toDouble(), health.toDouble(), red)?.grade

    @Test
    fun boundary95IsSPlus() {
        Assert.eq(Grade.S_PLUS, gradeOf(95))
    }

    @Test
    fun justBelow95IsS() {
        Assert.eq(Grade.S, gradeOf(94))
    }

    @Test
    fun boundary90IsS() {
        Assert.eq(Grade.S, gradeOf(90))
    }

    @Test
    fun justBelow90IsA() {
        Assert.eq(Grade.A, gradeOf(89))
    }

    @Test
    fun boundary80IsA() {
        Assert.eq(Grade.A, gradeOf(80))
    }

    @Test
    fun justBelow80IsB() {
        Assert.eq(Grade.B, gradeOf(79))
    }

    @Test
    fun boundary70IsB() {
        Assert.eq(Grade.B, gradeOf(70))
    }

    @Test
    fun justBelow70IsC() {
        Assert.eq(Grade.C, gradeOf(69))
    }

    @Test
    fun boundary60IsC() {
        Assert.eq(Grade.C, gradeOf(60))
    }

    @Test
    fun justBelow60IsD() {
        Assert.eq(Grade.D, gradeOf(59))
    }

    @Test
    fun zeroIsD() {
        Assert.eq(Grade.D, gradeOf(0))
    }

    @Test
    fun judgeBoundaryIsBeforeRounding() {
        val e = Scoring.evaluate(mapOf(Category.POWER to 100, Category.BRAKE_STEERING to 100, Category.TIRE to 100, Category.ELECTRIC to 100, Category.SAFETY to 100), 53)
        Assert.near(85.9, e.overall!!)
        Assert.eq(Grade.A, e.grade)
        Assert.eq(85, e.displayedOverall)
    }

    @Test
    fun ninetyPointFourWaitsForItsPoint() {
        val e = Scoring.evaluate(mapOf(Category.POWER to 100, Category.BRAKE_STEERING to 100, Category.TIRE to 100, Category.ELECTRIC to 100, Category.SAFETY to 100), 68)
        Assert.near(90.4, e.overall!!)
        Assert.eq(Grade.S, e.grade)
    }

    @Test
    fun redCapLimitsToB() {
        val e = Scoring.evaluate(mapOf(Category.POWER to 100, Category.BRAKE_STEERING to 100, Category.TIRE to 100, Category.ELECTRIC to 100, Category.SAFETY to 100), 100, redCap = true)
        Assert.near(100.0, e.overall!!)
        Assert.eq(Grade.B, e.grade)
        Assert.isTrue(e.redCap)
    }

    @Test
    fun redCapDoesNotRaiseALowGrade() {
        val e = Scoring.evaluate(mapOf(Category.POWER to 60, Category.BRAKE_STEERING to 100, Category.TIRE to 60, Category.ELECTRIC to 90, Category.SAFETY to 100), 0, redCap = true)
        Assert.near(57.4, e.overall!!)
        Assert.eq(Grade.D, e.grade)
        Assert.isFalse(e.redCap)
    }

    @Test
    fun e3AbsRedWithCare80IsCappedToB() {
        val snapshot = VehicleSnapshot.normal().copy(telltales = listOf(Fx.red(Telltale.ABS)))
        val e = Scoring.evaluate(snapshot, 80)
        Assert.near(87.0, e.overall!!)
        Assert.eq(Grade.B, e.grade)
        Assert.isTrue(e.redCap)
    }

    @Test
    fun healthBelow90CapsToA() {
        val decision = Scoring.gradeFor(95.0, 89.9, redCap = false)!!
        Assert.eq(Grade.A, decision.grade)
        Assert.isTrue(decision.healthCapped)
    }

    @Test
    fun health94Point9CapsSPlusToS() {
        val decision = Scoring.gradeFor(96.0, 94.9, redCap = false)!!
        Assert.eq(Grade.S, decision.grade)
        Assert.isTrue(decision.healthCapped)
    }

    @Test
    fun health95AllowsSPlus() {
        Assert.eq(Grade.S_PLUS, Scoring.gradeFor(96.0, 95.0, redCap = false)!!.grade)
    }

    @Test
    fun health90AllowsS() {
        Assert.eq(Grade.S, Scoring.gradeFor(91.0, 90.0, redCap = false)!!.grade)
    }

    @Test
    fun healthCapDoesNotTouchA() {
        val decision = Scoring.gradeFor(85.0, 70.0, redCap = false)!!
        Assert.eq(Grade.A, decision.grade)
        Assert.isFalse(decision.healthCapped)
    }

    @Test
    fun healthNoticeShownWhenOverallHighButHealthLow() {
        val e = Scoring.evaluate(mapOf(Category.POWER to 70, Category.BRAKE_STEERING to 70, Category.TIRE to 100, Category.ELECTRIC to 100, Category.SAFETY to 100), 100)
        Assert.near(88.0, e.health!!)
        Assert.near(91.6, e.overall!!)
        Assert.eq(Grade.A, e.grade)
        Assert.eq(Scoring.HEALTH_NOTICE, e.healthNotice)
    }

    @Test
    fun healthNoticeHiddenWhenHealthFine() {
        val e = Scoring.evaluate(mapOf(Category.POWER to 100, Category.BRAKE_STEERING to 100, Category.TIRE to 100, Category.ELECTRIC to 100, Category.SAFETY to 100), 100)
        Assert.eq(Grade.S_PLUS, e.grade)
        Assert.isNull(e.healthNotice)
    }

    @Test
    fun unmeasuredHealthGivesNoGrade() {
        val categories = mapOf(
            Category.POWER to null,
            Category.BRAKE_STEERING to null,
            Category.TIRE to null,
            Category.ELECTRIC to null,
            Category.SAFETY to null,
        )
        val e = Scoring.evaluate(categories, 52)
        Assert.isNull(e.health)
        Assert.isNull(e.overall)
        Assert.isNull(e.grade)
        Assert.isNull(e.displayedOverall)
        Assert.isNull(e.pointsToNext)
    }

    @Test
    fun displayedOverallFloors() {
        val e = Scoring.evaluate(
            mapOf(
                Category.POWER to 100,
                Category.BRAKE_STEERING to 100,
                Category.TIRE to 100,
                Category.ELECTRIC to 100,
                Category.SAFETY to 100,
            ),
            52,
        )
        Assert.near(85.6, e.overall!!)
        Assert.eq(85, e.displayedOverall)
    }

    @Test
    fun pointsToNextRoundsUp() {
        val e = Scoring.evaluate(mapOf(Category.POWER to 100, Category.BRAKE_STEERING to 100, Category.TIRE to 100, Category.ELECTRIC to 100, Category.SAFETY to 100), 52)
        Assert.near(85.6, e.overall!!)
        Assert.eq(Grade.A, e.grade)
        Assert.eq(Grade.S, e.nextGrade)
        Assert.eq(5, e.pointsToNext)
    }

    @Test
    fun pointsToNextIsExactAtBoundary() {
        val e = Scoring.evaluate(mapOf(Category.POWER to 100, Category.BRAKE_STEERING to 100, Category.TIRE to 100, Category.ELECTRIC to 100, Category.SAFETY to 100), 65)
        Assert.near(89.5, e.overall!!)
        Assert.eq(1, e.pointsToNext)
    }

    @Test
    fun topGradeHasNoNext() {
        val e = Scoring.evaluate(mapOf(Category.POWER to 100, Category.BRAKE_STEERING to 100, Category.TIRE to 100, Category.ELECTRIC to 100, Category.SAFETY to 100), 90)
        Assert.eq(Grade.S_PLUS, e.grade)
        Assert.isNull(e.nextGrade)
        Assert.isNull(e.pointsToNext)
    }

    @Test
    fun redCapNoticeShowsPathBack() {
        val snapshot = VehicleSnapshot.normal().copy(telltales = listOf(Fx.red(Telltale.ABS)))
        val e = Scoring.evaluate(snapshot, 80)
        Assert.eq(Grade.B, e.grade)
        Assert.notNull(e.redCapNotice)
        Assert.isTrue(e.redCapNotice!!.startsWith("빨간 경고등을 해결하면"), "C-15 문구")
        Assert.isTrue(e.redCapNotice!!.contains("S까지"), "C-15 목표 등급")
        Assert.isTrue(e.redCapNotice!!.endsWith("점"), "C-15 점수 표기")
    }

    @Test
    fun noRedNoticeWithoutRed() {
        val e = Scoring.evaluate(VehicleSnapshot.normal(), 52)
        Assert.isNull(e.redCapNotice)
    }

    @Test
    fun e1NormalFirstDayIsEightyFivePointSixA() {
        val care = Care.evaluate(listOf(Fx.insp("2026-11-05", VehicleSnapshot.normal())), Profile.REAL, Fx.day("2026-11-05"))
        val e = Scoring.evaluate(VehicleSnapshot.normal(), care.score)
        Assert.eq(52, care.score)
        Assert.near(100.0, e.health!!)
        Assert.near(85.6, e.overall!!)
        Assert.eq(Grade.A, e.grade)
    }

    @Test
    fun e2OilAndTpmsIsSeventySixPointFiveB() {
        val snapshot = VehicleSnapshot.normal().copy(
            engineOil = FluidLevel.LOW,
            lowTireWheels = setOf(WheelArea.FL),
            telltales = listOf(Fx.yellow(Telltale.TPMS)),
        )
        val e = Scoring.evaluate(snapshot, 52)
        Assert.eq(60, e.categories[Category.POWER])
        Assert.eq(75, e.categories[Category.TIRE])
        Assert.near(76.5, e.overall!!)
        Assert.eq(Grade.B, e.grade)
        Assert.eq(76, e.displayedOverall)
    }

    @Test
    fun floorAndCeilDisagreeOnPurpose() {
        val e = Scoring.evaluate(mapOf(Category.POWER to 100, Category.BRAKE_STEERING to 100, Category.TIRE to 100, Category.ELECTRIC to 100, Category.SAFETY to 100), 53)
        Assert.near(85.9, e.overall!!)
        Assert.eq(85, e.displayedOverall)
        Assert.eq(5, e.pointsToNext)
    }

    // ── 측정 항목이 적으면 등급을 주지 않는다 (정직성 규칙)
    // 실제 에뮬레이터에서 오일·브레이크액·전기·안전이 모두 측정 불가일 때
    // 타이어 1개만으로 "A · 종합 8"을 만들어 낸 결함을 막는다.

    @Test
    fun `측정된 카테고리가 2개면 등급이 없다`() {
        val categories = mapOf(
            Category.POWER to 100,
            Category.BRAKE_STEERING to 100,
            Category.TIRE to null,
            Category.ELECTRIC to null,
            Category.SAFETY to null,
        )
        val e = Scoring.evaluate(categories, care = 50)
        Assert.eq(null, e.grade)
        Assert.eq(null, e.overall)
        Assert.eq(null, e.displayedOverall)
        Assert.eq(2, e.categories.values.filterNotNull().size)
    }

    @Test
    fun `측정된 카테고리가 3개면 등급이 나온다`() {
        val categories = mapOf(
            Category.POWER to 100,
            Category.BRAKE_STEERING to 100,
            Category.TIRE to 100,
            Category.ELECTRIC to null,
            Category.SAFETY to null,
        )
        val e = Scoring.evaluate(categories, care = 50)
        Assert.eq(Grade.A, e.grade)
        Assert.eq(85, e.displayedOverall)
    }

    @Test
    fun `전부 측정 불가면 등급이 없고 건강도 없다`() {
        val categories = Category.entries.associateWith { null }
        val e = Scoring.evaluate(categories, care = 50)
        Assert.eq(null, e.grade)
        Assert.eq(null, e.health)
        // care 는 호출자가 넘긴 값이다(기록 없음 → 시즌 시작값 50). 등급·종합만 없으면 된다.
        Assert.eq(50, e.care)
    }

    @Test
    fun `MIN_MEASURED_CATEGORIES 는 3이다`() {
        Assert.eq(3, Scoring.MIN_MEASURED_CATEGORIES)
    }
}
