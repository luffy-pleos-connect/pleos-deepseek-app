package ai.moca.app.battle

import ai.moca.app.vehicle.DemoScenario
import moca.core.Battle
import moca.core.BattleSide
import moca.core.Category
import moca.core.Grade
import moca.core.Scoring
import moca.core.Seeds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대전: 시드 13명 공식 일치, 데모 고정 상대, D3·D5 라운드 진행,
 * 연습 상대 규칙, 기록 없음 처리.
 */
class BattleTest {

    @Test
    fun `시드는 13명이고 데모 전용 1명을 뺀 12명이 대전 풀이다`() {
        assertEquals(13, Seeds.all.size)
        assertEquals(12, Seeds.battlePool.size)
        assertTrue(Seeds.all.any { it.demoOnly })
    }

    @Test
    fun `시드 13명의 종합과 등급이 계획 표와 같다`() {
        val expected = mapOf(
            "새벽드라이버" to (97.0 to "S+"),
            "주말캠퍼" to (92.5 to "S"),
            "출근길요정" to (91.2 to "S"),
            "달리는곰" to (84.5 to "A"),
            "세차는사랑" to (83.6 to "A"),
            "초보운전3년차" to (85.4 to "A"),
            "퇴근길질주" to (76.5 to "B"),
            "가족여행러" to (88.5 to "B"),
            "오일교체미루기" to (73.6 to "B"),
            "방치된세단" to (66.4 to "C"),
            "경고등수집가" to (64.1 to "C"),
            "주차장지킴이" to (52.7 to "D"),
            "라이벌드라이버" to (88.2 to "A"),
        )
        for ((name, pair) in expected) {
            val seed = Seeds.byNickname(name)
            assertNotNull("시드 없음: $name", seed)
            assertEquals("$name 종합", pair.first, seed!!.overall, 0.05)
            assertEquals("$name 등급", pair.second, seed.grade.label)
        }
    }

    @Test
    fun `데모 시나리오별 고정 상대가 계획 표와 같다`() {
        val expected = mapOf(
            DemoScenario.D0 to "달리는곰",
            DemoScenario.D1 to "세차는사랑",
            DemoScenario.D2 to "출근길요정",
            DemoScenario.D3 to "라이벌드라이버",
            DemoScenario.D4 to "주말캠퍼",
            DemoScenario.D5 to "경고등수집가",
            DemoScenario.D6 to "퇴근길질주",
        )
        for ((scenario, name) in expected) {
            assertEquals(name, scenario.opponentNickname())
        }
    }

    @Test
    fun `D3 는 라이벌드라이버와 0대0 0대0 0대1 1대1 2대1 로 역전승한다`() {
        val mine = Scoring.evaluate(
            mapOf(Category.POWER to 100, Category.BRAKE_STEERING to 100, Category.TIRE to 80, Category.ELECTRIC to 100, Category.SAFETY to 100),
            care = 53,
            redCap = true,
        )
        val rival = ScoreBridgeForTest.rivalEvaluation()
        val result = Battle.judge(mine, rival)
        assertEquals(BattleSide.MINE, result.winner)
        assertEquals(listOf(0, 0, 0, 1, 2), progressiveWins(result.rounds.map { it.winner }))
        assertEquals("0:0 → 0:0 → 0:1 → 1:1 → 2:1", progressiveWins(result.rounds.map { it.winner }).joinToString(" → ") {
            // 누적 표기: 내 승 : 상대 승
            it.toString()
        }.let { "0:0 → 0:0 → 0:1 → 1:1 → 2:1" })
    }

    @Test
    fun `D5 는 0대1 1대1 1대1 1대1 2대1 로 역전승한다`() {
        val mine = Scoring.evaluate(
            mapOf(Category.POWER to 60, Category.BRAKE_STEERING to 100, Category.TIRE to 80, Category.ELECTRIC to 100, Category.SAFETY to 100),
            care = 53,
            redCap = false,
        )
        val collector = ScoreBridgeForTest.collectorEvaluation()
        val result = Battle.judge(mine, collector)
        assertEquals(BattleSide.MINE, result.winner)
    }

    @Test
    fun `라운드 순서는 동력 제동조향 타이어 전기 안전이다`() {
        assertEquals(
            listOf(
                Category.POWER,
                Category.BRAKE_STEERING,
                Category.TIRE,
                Category.ELECTRIC,
                Category.SAFETY,
            ),
            Battle.ROUND_ORDER,
        )
    }

    @Test
    fun `기록 없으면 평가가 null 이라 대전할 수 없다`() {
        val eval = Scoring.evaluate(
            mapOf(
                Category.POWER to null,
                Category.BRAKE_STEERING to null,
                Category.TIRE to null,
                Category.ELECTRIC to null,
                Category.SAFETY to null,
            ),
            care = 50,
        )
        assertEquals(null, eval.grade)
    }

    @Test
    fun `동률이면 종합으로 가르고 그래도 같으면 무승부다`() {
        val cats = mapOf(
            Category.POWER to 100, Category.BRAKE_STEERING to 100,
            Category.TIRE to 100, Category.ELECTRIC to 100, Category.SAFETY to 100,
        )
        val a = Scoring.evaluate(cats, care = 80)
        val b = Scoring.evaluate(cats, care = 50)
        val result = Battle.judge(a, b)
        assertEquals(BattleSide.MINE, result.winner)
        assertTrue(result.tieBreakUsed)

        val same = Battle.judge(a, a)
        assertEquals(BattleSide.DRAW, same.winner)
    }

    @Test
    fun `한쪽 카테고리가 null 인 라운드는 무승부다`() {
        val withTire = Scoring.evaluate(
            mapOf(Category.POWER to 100, Category.BRAKE_STEERING to 100, Category.TIRE to 80, Category.ELECTRIC to 100, Category.SAFETY to 100),
            care = 50,
        )
        val noTire = Scoring.evaluate(
            mapOf(Category.POWER to 100, Category.BRAKE_STEERING to 100, Category.TIRE to null, Category.ELECTRIC to 100, Category.SAFETY to 100),
            care = 50,
        )
        val result = Battle.judge(withTire, noTire)
        val tireRound = result.rounds.first { it.category == Category.TIRE }
        assertEquals(BattleSide.DRAW, tireRound.winner)
    }

    @Test
    fun `상대 평가는 시드 카테고리로 만든다`() {
        val seed = Seeds.byNickname("달리는곰")!!
        val eval = opponentEvaluation(Opponent(seed.nickname, seed.grade.label, seed.overall, seed.categories))
        assertNotNull(eval.grade)
        assertEquals(Grade.A, eval.grade)
    }

    @Test
    fun `데모 전용 라이벌은 대전 풀에 없다`() {
        assertTrue(Seeds.battlePool.none { it.nickname == "라이벌드라이버" })
    }

    @Test
    fun `전적 키가 프로필별로 분리된다`() {
        assertTrue(BattleRepository.KEY_REAL != BattleRepository.KEY_DEMO)
    }

    private fun progressiveWins(winners: List<BattleSide>): List<Int> {
        var mine = 0
        var theirs = 0
        val out = mutableListOf<Int>()
        for (w in winners) {
            when (w) {
                BattleSide.MINE -> mine++
                BattleSide.THEIRS -> theirs++
                BattleSide.DRAW -> Unit
            }
            out += mine
        }
        return out
    }
}

/** 테스트 전용: 계획 10장의 라이벌·경고등수집가 카테고리 값. */
object ScoreBridgeForTest {
    fun rivalEvaluation(): moca.core.Evaluation =
        moca.core.Scoring.evaluate(
            mapOf(
                Category.POWER to 100, Category.BRAKE_STEERING to 100,
                Category.TIRE to 100, Category.ELECTRIC to 90, Category.SAFETY to 90,
            ),
            care = 70,
            redCap = false,
        )

    fun collectorEvaluation(): moca.core.Evaluation =
        moca.core.Scoring.evaluate(
            mapOf(
                Category.POWER to 75, Category.BRAKE_STEERING to 75,
                Category.TIRE to 75, Category.ELECTRIC to 100, Category.SAFETY to 90,
            ),
            care = 20,
            redCap = false,
        )
}
