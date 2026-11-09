package moca.core

import kotlin.math.ceil
import kotlin.math.floor

class PropertyTest {

    private class Lcg(seed: Long) {
        private var state = seed
        fun nextInt(bound: Int): Int {
            state = (state * 6364136223846793005L + 1442695040888963407L)
            val value = (state ushr 33).toInt()
            return kotlin.math.abs(value % bound)
        }

        fun nextDouble(): Double = nextInt(10_000) / 10_000.0

        fun chance(percent: Int): Boolean = nextInt(100) < percent
    }

    private val telltales = listOf(
        Telltale.ENGINE, Telltale.ENGINE_OIL_PRESSURE, Telltale.ENGINE_TEMPERATURE,
        Telltale.EV_SYSTEM, Telltale.HEV_SYSTEM, Telltale.PARTICLE_FILTER,
        Telltale.ABS, Telltale.ESC, Telltale.MDPS, Telltale.TPMS,
        Telltale.LOW_VOLT_BATTERY, Telltale.POWER_DOWN, Telltale.AIRBAG,
        Telltale.MASTER_WARNING, Telltale.LOW_FUEL, Telltale.LOW_EV_BATTERY,
        Telltale.SEATBELT, Telltale.PASSENGER_AIRBAG_OFF,
    )

    private fun randomSnapshot(rnd: Lcg): VehicleSnapshot {
        val fluids = FluidLevel.entries
        val states = mutableListOf<TelltaleState>()
        for (telltale in telltales) {
            if (rnd.chance(18)) {
                val color = if (rnd.chance(50)) Color.RED else Color.YELLOW
                states += TelltaleState(telltale, color, rnd.chance(20))
            }
        }
        val wheels = WheelArea.entries.filter { rnd.chance(20) }.toSet()
        val unsupported = Item.entries.filter { rnd.chance(8) }.toSet()
        return VehicleSnapshot(
            engineOil = fluids[rnd.nextInt(fluids.size)],
            transmissionOil = fluids[rnd.nextInt(fluids.size)],
            brakeFluid = fluids[rnd.nextInt(fluids.size)],
            lowTireWheels = wheels,
            fuelPercent = if (rnd.chance(80)) rnd.nextDouble() * 100 else null,
            batteryPercent = if (rnd.chance(80)) rnd.nextDouble() * 100 else null,
            washerFluidLow = rnd.chance(25),
            airbagDeployed = rnd.chance(5),
            telltales = states,
            unsupported = unsupported,
        )
    }

    private fun randomCare(rnd: Lcg): Int = rnd.nextInt(101)

    private fun randomDefect(rnd: Lcg, snapshot: VehicleSnapshot): VehicleSnapshot {
        val roll = rnd.nextInt(6)
        return when (roll) {
            0 -> snapshot.copy(engineOil = FluidLevel.CRITICALLY_LOW)
            1 -> snapshot.copy(brakeFluid = FluidLevel.CRITICALLY_LOW)
            2 -> {
                val missing = WheelArea.entries.filter { it !in snapshot.lowTireWheels }
                if (missing.isEmpty()) snapshot else snapshot.copy(lowTireWheels = snapshot.lowTireWheels + missing[rnd.nextInt(missing.size)])
            }
            3 -> snapshot.copy(washerFluidLow = true)
            4 -> snapshot.copy(airbagDeployed = true)
            else -> {
                val telltale = telltales[rnd.nextInt(telltales.size)]
                val color = if (rnd.chance(50)) Color.RED else Color.YELLOW
                snapshot.copy(telltales = snapshot.telltales + TelltaleState(telltale, color))
            }
        }
    }

    @Test
    fun categoriesStayInsideZeroToHundred() {
        val rnd = Lcg(20261109L)
        var violations = 0
        repeat(10_000) {
            val categories = Scoring.categories(randomSnapshot(rnd))
            for ((_, value) in categories) {
                if (value != null && value !in 0..100) violations++
            }
        }
        Assert.eq(0, violations, "AT-05 점수 범위 위반")
    }

    @Test
    fun healthStaysInsideZeroToHundred() {
        val rnd = Lcg(7L)
        var violations = 0
        repeat(10_000) {
            val health = Scoring.health(Scoring.categories(randomSnapshot(rnd))) ?: return@repeat
            if (health < 0.0 || health > 100.0) violations++
        }
        Assert.eq(0, violations, "AT-05 건강 범위 위반")
    }

    @Test
    fun addingADefectNeverRaisesACategory() {
        val rnd = Lcg(424242L)
        var violations = 0
        var checks = 0
        repeat(10_000) {
            val before = randomSnapshot(rnd)
            val after = randomDefect(rnd, before)
            val a = Scoring.categories(before)
            val b = Scoring.categories(after)
            for (category in Category.values()) {
                val previous = a[category] ?: continue
                val next = b[category] ?: continue
                checks++
                if (next > previous) violations++
            }
        }
        Assert.isTrue(checks > 5_000, "비교 표본 $checks")
        Assert.eq(0, violations, "AT-05 감점 단조성 위반")
    }

    @Test
    fun addingADefectNeverRaisesHealthOrOverall() {
        val rnd = Lcg(998877L)
        var violations = 0
        var checks = 0
        repeat(10_000) {
            val before = randomSnapshot(rnd)
            val after = randomDefect(rnd, before)
            val care = randomCare(rnd)
            val a = Scoring.categories(before)
            val b = Scoring.categories(after)
            val measured = Category.entries.filter { a[it] != null }
            if (measured != Category.entries.filter { b[it] != null }) return@repeat
            if (measured.isEmpty()) return@repeat
            checks++
            val h1 = Scoring.health(a)!!
            val h2 = Scoring.health(b)!!
            if (h2 > h1 + 1e-9) violations++
            val o1 = Scoring.overall(h1, care)!!
            val o2 = Scoring.overall(h2, care)!!
            if (o2 > o1 + 1e-9) violations++
        }
        Assert.isTrue(checks > 3_000, "비교 표본 $checks")
        Assert.eq(0, violations, "AT-05 종합 단조성 위반")
    }

    @Test
    fun evaluationIsDeterministic() {
        val rnd = Lcg(31337L)
        var violations = 0
        repeat(10_000) {
            val snapshot = randomSnapshot(rnd)
            val care = randomCare(rnd)
            val first = Scoring.evaluate(snapshot, care)
            val second = Scoring.evaluate(snapshot, care)
            if (first != second) violations++
        }
        Assert.eq(0, violations, "AT-05 결정성 위반")
    }

    @Test
    fun redCapNeverExceedsB() {
        val rnd = Lcg(2026L)
        var violations = 0
        var redCases = 0
        repeat(10_000) {
            val snapshot = randomSnapshot(rnd)
            val care = randomCare(rnd)
            val evaluation = Scoring.evaluate(snapshot, care)
            if (Rules.hasRed(snapshot)) {
                redCases++
                val grade = evaluation.grade
                if (grade != null && grade.ordinal > Grade.B.ordinal) violations++
            }
        }
        Assert.isTrue(redCases > 500, "RED 표본 $redCases")
        Assert.eq(0, violations, "AT-05 RED 상한 위반")
    }

    @Test
    fun displayedOverallIsFloorAndPointsAreCeiling() {
        val rnd = Lcg(555L)
        var violations = 0
        repeat(10_000) {
            val snapshot = randomSnapshot(rnd)
            val evaluation = Scoring.evaluate(snapshot, randomCare(rnd))
            val overall = evaluation.overall ?: return@repeat
            val displayed = evaluation.displayedOverall!!
            if (displayed != floor(overall + 1e-9).toInt()) violations++
            if (displayed > overall) violations++
            val next = evaluation.nextGrade
            if (next != null) {
                val expected = kotlin.math.max(0, ceil(next.threshold - overall - 1e-9).toInt())
                if (evaluation.pointsToNext != expected) violations++
            }
        }
        Assert.eq(0, violations, "AT-05 표시/남은 점수 위반")
    }

    @Test
    fun gradeMonotoneInOverall() {
        val rnd = Lcg(8080L)
        var violations = 0
        repeat(5_000) {
            val low = rnd.nextInt(101).toDouble()
            val high = low + rnd.nextInt(20)
            val a = Scoring.gradeFor(low, 100.0, false)!!
            val b = Scoring.gradeFor(high, 100.0, false)!!
            if (b.grade.ordinal < a.grade.ordinal) violations++
        }
        Assert.eq(0, violations, "AT-05 등급 단조성 위반")
    }

    @Test
    fun careStaysInsideZeroToHundred() {
        val rnd = Lcg(909090L)
        var violations = 0
        repeat(2_000) {
            val start = Fx.day("2026-11-01")
            val records = (0 until rnd.nextInt(30)).map { index ->
                Inspection(
                    Profile.REAL,
                    start.plusDays(index.toLong()),
                    randomSnapshot(rnd),
                    seq = index.toLong(),
                )
            }
            val today = start.plusDays(rnd.nextInt(40).toLong())
            val score = Care.evaluate(records, Profile.REAL, today).score
            if (score !in 0..100) violations++
        }
        Assert.eq(0, violations, "AT-05 관리 범위 위반")
    }

    @Test
    fun careIsDeterministic() {
        val rnd = Lcg(1212L)
        var violations = 0
        repeat(1_000) {
            val start = Fx.day("2026-11-01")
            val records = (0 until rnd.nextInt(20)).map { index ->
                Inspection(Profile.DEMO, start.plusDays(index.toLong() * 2), randomSnapshot(rnd), seq = index.toLong())
            }
            val today = start.plusDays(60)
            val first = Care.evaluate(records, Profile.DEMO, today).score
            val second = Care.evaluate(records.reversed(), Profile.DEMO, today).score
            if (first != second) violations++
        }
        Assert.eq(0, violations, "AT-05 관리 결정성 위반")
    }

    @Test
    fun battleWinnerIsAntisymmetric() {
        val rnd = Lcg(6789L)
        var violations = 0
        repeat(5_000) {
            val a = Scoring.evaluate(randomSnapshot(rnd), randomCare(rnd))
            val b = Scoring.evaluate(randomSnapshot(rnd), randomCare(rnd))
            val forward = Battle.judge(a, b).winner
            val backward = Battle.judge(b, a).winner
            val mirrored = when (forward) {
                BattleSide.MINE -> BattleSide.THEIRS
                BattleSide.THEIRS -> BattleSide.MINE
                BattleSide.DRAW -> BattleSide.DRAW
            }
            if (mirrored != backward) violations++
        }
        Assert.eq(0, violations, "AT-05 대전 대칭 위반")
    }
}
