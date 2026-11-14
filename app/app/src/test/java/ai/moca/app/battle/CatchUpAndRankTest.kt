package ai.moca.app.battle

import moca.core.Battle
import moca.core.BattleSide
import moca.core.Category
import moca.core.Demo
import moca.core.Item
import moca.core.Rules
import moca.core.Scoring
import moca.core.Seeds
import moca.core.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 개발 문서 W10(«이 차만큼 관리하려면»)·W11(월간랭킹). 최종정리 7장 기능 1·3·4·6번, 12장.
 * lead 가 쓴 테스트다. 워커는 이 파일을 고치지 않는다.
 *
 * 약속(ai.moca.app.battle):
 *   data class CatchUpItem(val category: Category, val mine: Int?, val theirs: Int?,
 *                          val fixes: List<moca.core.Deduction>,   감점 큰 순으로, 빼면 상대 점수 이상이 되는 데까지
 *                          val after: Int?,                        fixes 를 다 해결했을 때 내 점수
 *                          val reaches: Boolean)                   after >= theirs 인가
 *   data class CatchUp(val lost: List<CatchUpItem>,               내가 진 구역(구역 순서)
 *                      val ahead: List<Category>,                  내가 앞선 구역(구역 순서)
 *                      val unmeasured: List<Category>)             내 점수가 측정 불가라 비교할 수 없는 구역
 *   object CatchUpPlanner { fun of(mySnapshot: VehicleSnapshot, mine: moca.core.Evaluation, theirs: moca.core.Evaluation): CatchUp }
 *
 *   data class MonthlyRow(val nickname: String, val overall: Int, val care: Int, val gradeLabel: String, val me: Boolean)
 *   data class MonthlyRanking(val rows: List<MonthlyRow>, val myRank: Int, val total: Int, val leader: MonthlyRow, val gapToLeader: Int)
 *   object MonthlyRank { fun of(myNickname: String, mine: moca.core.Evaluation, seeds: List<moca.core.Seed> = Seeds.battlePool): MonthlyRanking? }
 *       순위는 화면에 보이는 종합 점수(소수점 버림)가 높은 순, 같으면 관리 점수가 높은 쪽이 위, 그것도 같으면 내가 위. 등급이 없으면 null.
 *       gapToLeader 는 화면에 보이는 종합 점수끼리의 차이(1위가 나면 0).
 */
class CatchUpAndRankTest {

    private fun eval(snapshot: VehicleSnapshot, care: Int = 53) = Scoring.evaluate(snapshot, care, Rules.hasRed(snapshot))
    private fun seed(name: String) = Seeds.byNickname(name)!!.evaluation()

    // ── 대전 결과 (B-08, B-09) ───────────────────────────────────────

    @Test
    fun `데모 D3 대 라이벌드라이버는 무 패 패 승 승 2대2 종합 75대88 로 진다`() {
        val result = Battle.judge(eval(Demo.snapshotOf("D3")), seed("라이벌드라이버"))
        assertEquals(
            listOf(BattleSide.DRAW, BattleSide.THEIRS, BattleSide.THEIRS, BattleSide.MINE, BattleSide.MINE),
            result.rounds.map { it.winner },
        )
        assertEquals(2 to 2, result.myWins to result.theirWins)
        assertEquals(BattleSide.THEIRS, result.winner)
        assertEquals(75 to 88, result.myOverall!!.toInt() to result.theirOverall!!.toInt())
    }

    @Test
    fun `데모 D5 대 경고등수집가는 패 승 무 무 승 2대1 로 이긴다`() {
        val result = Battle.judge(eval(Demo.snapshotOf("D5")), seed("경고등수집가"))
        assertEquals(
            listOf(BattleSide.THEIRS, BattleSide.MINE, BattleSide.DRAW, BattleSide.DRAW, BattleSide.MINE),
            result.rounds.map { it.winner },
        )
        assertEquals(BattleSide.MINE, result.winner)
    }

    // ── 이 차만큼 관리하려면 ─────────────────────────────────────────

    @Test
    fun `데모 D3 는 ABS 경고등과 타이어 공기압을 해결하면 따라잡고 전기 안전에서 앞섰다`() {
        val snapshot = Demo.snapshotOf("D3")
        val plan = CatchUpPlanner.of(snapshot, eval(snapshot), seed("라이벌드라이버"))
        assertEquals(listOf(Category.BRAKE_STEERING, Category.TIRE), plan.lost.map { it.category })
        val brake = plan.lost[0]
        assertEquals(50 to 100, brake.mine to brake.theirs)
        assertEquals(listOf("ABS 경고등"), brake.fixes.map { it.label })
        assertEquals(100, brake.after)
        assertTrue(brake.reaches)
        val tire = plan.lost[1]
        assertEquals(75 to 100, tire.mine to tire.theirs)
        assertEquals(listOf("타이어 공기압 경고등"), tire.fixes.map { it.label })
        assertEquals(100, tire.after)
        assertEquals(listOf(Category.ELECTRIC, Category.SAFETY), plan.ahead)
        assertTrue(plan.unmeasured.isEmpty())
    }

    @Test
    fun `진 구역이 없으면 앞선 구역만 남는다`() {
        val snapshot = Demo.snapshotOf("D0")
        val plan = CatchUpPlanner.of(snapshot, eval(snapshot), seed("경고등수집가"))
        assertTrue(plan.lost.isEmpty())
        assertTrue(plan.ahead.isNotEmpty())
    }

    @Test
    fun `상대 점수에 닿을 만큼만 고칠 것을 고른다`() {
        // 안전: 에어백 전개 -50, 마스터 경고 노랑 -25, 워셔액 -10 → 15점. 상대 안전이 90 이면 50 과 25 를 해결해야 90 이 된다.
        val snapshot = VehicleSnapshot.normal().copy(
            airbagDeployed = true,
            washerFluidLow = true,
            telltales = listOf(moca.core.TelltaleState(moca.core.Telltale.MASTER_WARNING, moca.core.Color.YELLOW)),
        )
        val mine = eval(snapshot)
        assertEquals(15, mine.categories[Category.SAFETY])
        val theirs = Scoring.evaluate(Category.entries.associateWith<Category, Int?> { if (it == Category.SAFETY) 90 else 100 }, 70, false)
        val safety = CatchUpPlanner.of(snapshot, mine, theirs).lost.single { it.category == Category.SAFETY }
        assertEquals(listOf(50, 25), safety.fixes.map { it.points })
        assertEquals(90, safety.after)
        assertTrue(safety.reaches)
    }

    @Test
    fun `다 고쳐도 못 닿으면 그렇다고 알려 준다`() {
        // 내 타이어 80(한 바퀴 저압). 상대가 100 이면 해결하면 100. 상대 점수가 내 최대보다 높을 수는 없으므로,
        // 감점이 없는데 진 경우(측정 항목이 달라 생길 수 없는 경우)는 fixes 가 비고 reaches 가 false 다.
        val snapshot = VehicleSnapshot.normal()
        val mine = Scoring.evaluate(Category.entries.associateWith<Category, Int?> { if (it == Category.POWER) 90 else 100 }, 53, false)
        val theirs = Scoring.evaluate(Category.entries.associateWith<Category, Int?> { 100 }, 53, false)
        val power = CatchUpPlanner.of(snapshot, mine, theirs).lost.single()
        assertTrue(power.fixes.isEmpty())
        assertFalse(power.reaches)
    }

    @Test
    fun `내 구역이 측정 불가면 비교할 수 없는 구역으로 따로 알린다`() {
        val snapshot = Demo.snapshotOf("D6")
        val plan = CatchUpPlanner.of(snapshot, eval(snapshot), seed("퇴근길질주"))
        assertEquals(listOf(Category.TIRE), plan.unmeasured)
        assertTrue(plan.lost.none { it.category == Category.TIRE })
        assertTrue(plan.ahead.none { it == Category.TIRE })
        assertTrue(Item.TIRE in snapshot.unsupported)
    }

    // ── 월간랭킹 ─────────────────────────────────────────────────────

    @Test
    fun `데모 D3 는 열세 명 중 9위이고 1위는 새벽드라이버 97점 차이는 22점이다`() {
        val ranking = MonthlyRank.of("나", eval(Demo.snapshotOf("D3")))!!
        assertEquals(13, ranking.total)
        assertEquals(9, ranking.myRank)
        assertEquals("새벽드라이버", ranking.leader.nickname)
        assertEquals(97, ranking.leader.overall)
        assertEquals(22, ranking.gapToLeader)
        assertEquals(
            listOf(
                "새벽드라이버" to 97, "주말캠퍼" to 92, "출근길요정" to 91, "가족여행러" to 88, "초보운전3년차" to 85,
                "달리는곰" to 84, "세차는사랑" to 83, "퇴근길질주" to 76, "나" to 75, "오일교체미루기" to 73,
                "방치된세단" to 66, "경고등수집가" to 64, "주차장지킴이" to 52,
            ),
            ranking.rows.map { it.nickname to it.overall },
        )
        assertEquals(listOf("S+", "S", "S", "B", "A", "A", "A", "B", "B", "B", "C", "C", "D"), ranking.rows.map { it.gradeLabel })
        assertEquals(listOf(90, 75, 80, 85, 70, 60, 50, 52, 53, 40, 30, 20, 10), ranking.rows.map { it.care })
        assertTrue(ranking.rows[8].me)
        assertEquals(1, ranking.rows.count { it.me })
        assertTrue("연습 상대에 데모 전용 상대(라이벌드라이버)는 없다", ranking.rows.none { it.nickname == "라이벌드라이버" })
    }

    @Test
    fun `보이는 종합 점수가 같으면 관리 점수가 높은 쪽이 위다`() {
        val all100 = Category.entries.associateWith<Category, Int?> { 100 }
        val mine = Scoring.evaluate(all100, 50, false)
        assertEquals("전제: 내 종합은 85", 85, mine.displayedOverall)
        val sameCare = moca.core.Seed("쌍둥이", 100, 100, 100, 100, 100, 50, 85.0, moca.core.Grade.A)
        val moreCare = moca.core.Seed("관리왕", 100, 100, 100, 100, 100, 52, 85.6, moca.core.Grade.A)
        assertEquals("전제: 관리왕도 보이는 종합은 85", 85, moreCare.evaluation().displayedOverall)

        val tie = MonthlyRank.of("나", mine, listOf(sameCare))!!
        assertEquals("관리 점수까지 같으면 내가 위", listOf("나", "쌍둥이"), tie.rows.map { it.nickname })
        assertEquals(1, tie.myRank)
        assertEquals(0, tie.gapToLeader)

        val behind = MonthlyRank.of("나", mine, listOf(moreCare, sameCare))!!
        assertEquals(listOf("관리왕", "나", "쌍둥이"), behind.rows.map { it.nickname })
        assertEquals(2, behind.myRank)
        assertEquals("보이는 점수가 같으므로 차이는 0", 0, behind.gapToLeader)
    }

    @Test
    fun `등급이 없으면 순위를 내지 않는다`() {
        val poor = VehicleSnapshot.normal().copy(
            unsupported = setOf(Item.ENGINE_OIL, Item.TRANSMISSION_OIL, Item.BRAKE_FLUID, Item.TIRE, Item.WASHER_FLUID, Item.AIRBAG),
        )
        assertNull(MonthlyRank.of("나", eval(poor)))
    }
}
