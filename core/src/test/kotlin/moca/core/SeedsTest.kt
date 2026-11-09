package moca.core

class SeedsTest {

    private val expected = listOf(
        listOf("새벽드라이버", 100, 100, 100, 100, 100, 90, 97.0, "S+"),
        listOf("주말캠퍼", 100, 100, 100, 100, 100, 75, 92.5, "S"),
        listOf("출근길요정", 100, 100, 80, 100, 100, 80, 91.2, "S"),
        listOf("달리는곰", 100, 100, 75, 100, 100, 60, 84.5, "A"),
        listOf("세차는사랑", 100, 100, 100, 90, 100, 50, 83.6, "A"),
        listOf("초보운전3년차", 60, 100, 100, 100, 100, 70, 85.4, "A"),
        listOf("퇴근길질주", 60, 100, 75, 100, 100, 52, 76.5, "B"),
        listOf("가족여행러", 100, 50, 100, 100, 100, 85, 88.5, "B"),
        listOf("오일교체미루기", 60, 100, 100, 90, 90, 40, 73.6, "B"),
        listOf("방치된세단", 60, 100, 60, 90, 100, 30, 66.4, "C"),
        listOf("경고등수집가", 75, 75, 75, 100, 90, 20, 64.1, "C"),
        listOf("주차장지킴이", 60, 60, 60, 75, 100, 10, 52.7, "D"),
        listOf("라이벌드라이버", 100, 100, 100, 90, 90, 70, 88.2, "A"),
    )

    @Test
    fun thereAreThirteenSeeds() {
        Assert.eq(13, Seeds.all.size)
    }

    @Test
    fun seedCountsMatchThePlanTable() {
        Assert.eq(12, Seeds.battlePool.size, "데모 전용 1명을 뺀 12명")
        Assert.isTrue(Seeds.byNickname("라이벌드라이버")!!.demoOnly)
    }

    @Test
    fun everySeedMatchesThePlanTable() {
        Assert.eq(13, expected.size)
        for (row in expected) {
            val nickname = row[0] as String
            val seed = Seeds.byNickname(nickname)
            Assert.notNull(seed, nickname)
            Assert.eq(row[1], seed!!.power, "$nickname power")
            Assert.eq(row[2], seed.brake, "$nickname brake")
            Assert.eq(row[3], seed.tire, "$nickname tire")
            Assert.eq(row[4], seed.electric, "$nickname electric")
            Assert.eq(row[5], seed.safety, "$nickname safety")
            Assert.eq(row[6], seed.care, "$nickname care")
            Assert.near(row[7] as Double, seed.overall, 1e-9, "$nickname overall")
            Assert.eq(row[8], seed.grade.label, "$nickname grade")
        }
    }

    @Test
    fun everySeedOverallFollowsTheFormula() {
        for (seed in Seeds.all) {
            val computed = Scoring.overall(seed.health, seed.care)!!
            Assert.near(seed.overall, computed, 0.05, "${seed.nickname} 종합")
        }
    }

    @Test
    fun everySeedGradeFollowsTheRules() {
        for (seed in Seeds.all) {
            val evaluation = seed.evaluation()
            Assert.near(seed.overall, evaluation.overall!!, 1e-9, "${seed.nickname} 재계산")
            Assert.eq(seed.grade, evaluation.grade, "${seed.nickname} 등급")
        }
    }

    @Test
    fun redCappedSeedIsFamilyTraveler() {
        val seed = Seeds.byNickname("가족여행러")!!
        Assert.isTrue(seed.redCap)
        Assert.eq(Grade.B, seed.evaluation().grade)
        Assert.near(88.5, seed.evaluation().overall!!)
    }

    @Test
    fun onlyFamilyTravelerIsRedCapped() {
        Assert.eq(listOf("가족여행러"), Seeds.all.filter { it.redCap }.map { it.nickname })
    }

    @Test
    fun seedNicknamesAreUnique() {
        Assert.eq(Seeds.all.size, Seeds.all.map { it.nickname }.toSet().size)
    }

    @Test
    fun seedCategoriesAreInsideZeroToHundred() {
        for (seed in Seeds.all) {
            for ((category, value) in seed.categories) {
                Assert.notNull(value, "${seed.nickname} $category")
                Assert.isTrue(value!! in 0..100, "${seed.nickname} $category 범위")
            }
        }
    }

    @Test
    fun seedsJsonResourceIsBundled() {
        val stream = Seeds::class.java.getResourceAsStream("/moca/core/seeds.json")
        Assert.notNull(stream, "seeds.json 리소스")
    }
}
