package ai.moca.app.battle

import moca.core.Color
import moca.core.Demo
import moca.core.Inspection
import moca.core.Profile
import moca.core.Telltale
import moca.core.TelltaleState
import moca.core.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 개발 문서 W11: 월간랭킹 화면의 «이번 달 내 관리 요약»에 넣는 숫자. 최종정리 7장 기능 7번, 11.1절.
 * lead 가 쓴 테스트다. 워커는 이 파일을 고치지 않는다. 숫자는 기록에서 세고 지어내지 않는다.
 *
 * 약속(ai.moca.app.battle):
 *   data class CareSummary(val daysInspected: Int,      이번 달에 점검한 날 수
 *                          val streak: Int,             오늘부터 거꾸로 하루도 빠지지 않고 점검한 날 수(오늘 점검이 없으면 0)
 *                          val resolved: Int,           이번 달에 해결한 감점 묶음 수(앞 점검에 있던 묶음이 다음 점검에서 사라진 것. 읽지 못해 안 보인 것은 세지 않는다)
 *                          val gapToLeader: Int?,       1위와의 점수 차이(순위가 없으면 null)
 *                          val tips: List<String>)      따라잡는 방법: 지금 내 감점 중 큰 것부터 최대 2개의 이름
 *   object CareSummaryBuilder {
 *       fun of(records: List<Inspection>, today: LocalDate, ranking: MonthlyRanking?): CareSummary
 *           records 는 시간순. 지금 감점은 마지막 기록의 스냅샷에서 본다.
 *   }
 */
class CareSummaryTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 14)
    private fun rec(date: LocalDate, snapshot: VehicleSnapshot, seq: Long) = Inspection(Profile.REAL, date, snapshot, seq)
    private val normal = VehicleSnapshot.normal()
    private val abs = normal.copy(telltales = listOf(TelltaleState(Telltale.ABS, Color.RED)))

    @Test
    fun `데모 D3 는 이번 달 하루 점검했고 해결한 것이 없으며 1위와 22점 차이다`() {
        val records = Demo.recordsOf("D3", today)
        val snapshot = records.last().snapshot
        val ranking = MonthlyRank.of("나", moca.core.Scoring.evaluate(snapshot, 53, moca.core.Rules.hasRed(snapshot)))
        val s = CareSummaryBuilder.of(records, today, ranking)
        assertEquals(1, s.daysInspected)
        assertEquals(1, s.streak)
        assertEquals(0, s.resolved)
        assertEquals(22, s.gapToLeader)
        assertEquals(listOf("ABS 경고등", "타이어 공기압 경고등"), s.tips)
    }

    @Test
    fun `데모 D3 에서 ABS 를 해결하면 해결한 항목이 1개다`() {
        val s = CareSummaryBuilder.of(Demo.resolvedD3(today), today, null)
        assertEquals(1, s.resolved)
        assertEquals(1, s.daysInspected)
        assertEquals(listOf("타이어 공기압 경고등"), s.tips)
        assertEquals(null, s.gapToLeader)
    }

    @Test
    fun `이번 달 점검한 날과 연속 점검을 센다`() {
        val records = listOf(
            rec(today.minusDays(20), normal, 0),            // 지난달 (10월 25일)
            rec(today.minusDays(9), normal, 1),             // 11월 5일
            rec(today.minusDays(2), normal, 2),
            rec(today.minusDays(1), normal, 3),
            rec(today, normal, 4),
            rec(today, normal, 5),                          // 같은 날 두 번
        )
        val s = CareSummaryBuilder.of(records, today, null)
        assertEquals("지난달 것은 세지 않고 같은 날은 한 번만", 4, s.daysInspected)
        assertEquals(3, s.streak)
        assertEquals(0, s.resolved)
        assertTrue(s.tips.isEmpty())
    }

    @Test
    fun `오늘 점검이 없으면 연속 점검은 0 이다`() {
        val s = CareSummaryBuilder.of(listOf(rec(today.minusDays(1), normal, 0)), today, null)
        assertEquals(0, s.streak)
        assertEquals(1, s.daysInspected)
    }

    @Test
    fun `기록이 없으면 전부 0 이다`() {
        val s = CareSummaryBuilder.of(emptyList(), today, null)
        assertEquals(0, s.daysInspected)
        assertEquals(0, s.streak)
        assertEquals(0, s.resolved)
        assertTrue(s.tips.isEmpty())
    }

    @Test
    fun `읽지 못해 안 보인 감점은 해결로 세지 않고 지난달에 해결한 것도 세지 않는다`() {
        val unread = normal.copy(unsupported = setOf(moca.core.Item.TELLTALES))
        val hidden = CareSummaryBuilder.of(listOf(rec(today.minusDays(1), abs, 0), rec(today, unread, 1)), today, null)
        assertEquals(0, hidden.resolved)
        val lastMonth = CareSummaryBuilder.of(
            listOf(rec(today.minusDays(30), abs, 0), rec(today.minusDays(29), normal, 1), rec(today, normal, 2)), today, null,
        )
        assertEquals(0, lastMonth.resolved)
        val thisMonth = CareSummaryBuilder.of(listOf(rec(today.minusDays(3), abs, 0), rec(today, normal, 1)), today, null)
        assertEquals(1, thisMonth.resolved)
    }
}
