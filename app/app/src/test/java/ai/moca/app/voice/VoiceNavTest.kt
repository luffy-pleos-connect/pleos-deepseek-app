package ai.moca.app.voice

import ai.moca.app.nav.PlaceKind
import moca.core.Color
import moca.core.Demo
import moca.core.FluidLevel
import moca.core.Telltale
import moca.core.TelltaleState
import moca.core.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 개발 문서 W9(보이스챗)의 길 안내 말 알아듣기·먼저 제안. 최종정리 6장 기능 4~6·8·9번.
 * lead 가 쓴 테스트다. 워커는 이 파일을 고치지 않는다.
 *
 * 약속(ai.moca.app.voice):
 *   enum class NavAction { GO, ADD_STOP, CANCEL }
 *   data class NavRequest(val action: NavAction, val kind: PlaceKind? = null, val keyword: String? = null)
 *   object NavIntents {
 *       fun detect(question: String): NavRequest?      길 안내 말이 아니면 null
 *       fun isYes(answer: String): Boolean             확인 카드가 떠 있을 때의 승낙 («응», «안내해줘» …)
 *       fun isNo(answer: String): Boolean              거절 («아니», «취소» …)
 *   }
 *   object NavSuggestion { fun of(snapshot: VehicleSnapshot): NavRequest? }   화면에 들어올 때 한 번 먼저 제안할 것. 없으면 null
 */
class VoiceNavTest {

    private fun go(kind: PlaceKind) = NavRequest(NavAction.GO, kind = kind)
    private fun stop(kind: PlaceKind) = NavRequest(NavAction.ADD_STOP, kind = kind)
    private val cancel = NavRequest(NavAction.CANCEL)

    // ── 말 알아듣기 ──────────────────────────────────────────────────

    @Test
    fun `가까운 곳 안내 말을 종류별로 알아듣는다`() {
        val cases = mapOf(
            "가까운 정비소 안내해줘" to PlaceKind.REPAIR_SHOP,
            "정비소 안내해줘" to PlaceKind.REPAIR_SHOP,
            "카센터 찾아줘" to PlaceKind.REPAIR_SHOP,
            "근처 서비스센터로 가자" to PlaceKind.REPAIR_SHOP,
            "타이어점 안내해 줘" to PlaceKind.TIRE_SHOP,
            "주유소 안내해줘" to PlaceKind.GAS_STATION,
            "기름 넣으러 가자" to PlaceKind.GAS_STATION,
            "충전소 안내해줘" to PlaceKind.CHARGING_STATION,
            "가까운 전기차 충전소 찾아줘" to PlaceKind.CHARGING_STATION,
        )
        for ((question, kind) in cases) assertEquals(question, go(kind), NavIntents.detect(question))
    }

    @Test
    fun `들르자는 말은 경유지다`() {
        assertEquals(stop(PlaceKind.GAS_STATION), NavIntents.detect("주유소 들를래"))
        assertEquals(stop(PlaceKind.GAS_STATION), NavIntents.detect("가는 길에 주유소 들렀다 가자"))
        assertEquals(stop(PlaceKind.CHARGING_STATION), NavIntents.detect("충전소 경유해줘"))
        assertEquals(stop(PlaceKind.REPAIR_SHOP), NavIntents.detect("정비소 들러줘"))
    }

    @Test
    fun `안내를 끄라는 말을 알아듣는다`() {
        for (question in listOf("안내 취소해줘", "길 안내 꺼줘", "내비 취소", "안내 그만해", "경로 취소해 줘")) {
            assertEquals(question, cancel, NavIntents.detect(question))
        }
    }

    @Test
    fun `말한 장소로 가자는 말에서 장소 이름을 꺼낸다`() {
        assertEquals(NavRequest(NavAction.GO, keyword = "강남역"), NavIntents.detect("강남역으로 안내해줘"))
        assertEquals(NavRequest(NavAction.GO, keyword = "서울시청"), NavIntents.detect("서울시청 가자"))
        assertEquals(NavRequest(NavAction.GO, keyword = "인천공항"), NavIntents.detect("인천공항까지 길 안내해 줘"))
        assertEquals(NavRequest(NavAction.GO, keyword = "코엑스"), NavIntents.detect("코엑스로 가줘"))
    }

    @Test
    fun `길 안내가 아닌 말은 건드리지 않는다`() {
        for (question in listOf(
            "타이어 공기압 어때?", "이 경고등 뭐야?", "내 등급 알려줘", "배터리 얼마나 남았어?", "정비소 가야 해?",
            "엔진오일 왜 감점됐어?", "대전하자", "어떻게 해결해?", "충전은 언제 해야 해?", "",
        )) {
            assertNull(question, NavIntents.detect(question))
        }
    }

    @Test
    fun `확인 카드의 승낙과 거절을 알아듣는다`() {
        for (yes in listOf("응", "네", "어", "그래", "좋아", "안내해줘", "안내 시작", "시작해", "가자", "응 안내해줘")) assertTrue(yes, NavIntents.isYes(yes))
        for (no in listOf("아니", "아니요", "취소", "취소해", "됐어", "하지 마", "괜찮아")) assertTrue(no, NavIntents.isNo(no))
        for (other in listOf("타이어 어때?", "왜?", "")) {
            assertFalse(other, NavIntents.isYes(other))
            assertFalse(other, NavIntents.isNo(other))
        }
        assertFalse("거절이 승낙으로 들리면 안 된다", NavIntents.isYes("아니 안내하지 마"))
        assertTrue(NavIntents.isNo("아니 안내하지 마"))
    }

    // ── 먼저 제안 (기능 5번: 빨간 경고등 → 정비 항목 → 연료·배터리) ───

    @Test
    fun `빨간 경고등이 있으면 정비소를 먼저 제안한다`() {
        assertEquals(go(PlaceKind.REPAIR_SHOP), NavSuggestion.of(Demo.snapshotOf("D3")))
        val redAndLowBattery = VehicleSnapshot.normal().copy(
            batteryPercent = 5.0,
            telltales = listOf(TelltaleState(Telltale.ENGINE, Color.RED)),
        )
        assertEquals("여러 개가 해당해도 하나만", go(PlaceKind.REPAIR_SHOP), NavSuggestion.of(redAndLowBattery))
    }

    @Test
    fun `정비가 필요한 항목이 있으면 정비소를 제안한다`() {
        assertEquals(go(PlaceKind.REPAIR_SHOP), NavSuggestion.of(Demo.snapshotOf("D1")))                 // 엔진오일 부족
        assertEquals(go(PlaceKind.REPAIR_SHOP), NavSuggestion.of(Demo.snapshotOf("D2")))                 // 타이어 저압 + 노란 경고등
        assertEquals(go(PlaceKind.REPAIR_SHOP), NavSuggestion.of(VehicleSnapshot.normal().copy(brakeFluid = FluidLevel.LOW, batteryPercent = 3.0)))
    }

    @Test
    fun `정비 항목이 없고 잔량이 10퍼센트 미만이면 충전소나 주유소를 제안한다`() {
        assertEquals(go(PlaceKind.CHARGING_STATION), NavSuggestion.of(Demo.snapshotOf("D4")))            // 배터리 9%
        assertEquals(go(PlaceKind.GAS_STATION), NavSuggestion.of(VehicleSnapshot.normal().copy(fuelPercent = 4.0, batteryPercent = null)))
    }

    @Test
    fun `제안할 것이 없으면 아무것도 제안하지 않는다`() {
        assertNull(NavSuggestion.of(Demo.snapshotOf("D0")))
        assertNull("워셔액은 용품 구매라 길 안내를 제안하지 않는다", NavSuggestion.of(Demo.snapshotOf("D7")))
        assertNull(NavSuggestion.of(Demo.snapshotOf("D6")))
    }

    // ── 기기 안 의도(서버가 안 될 때)의 순서 ─────────────────────────

    @Test
    fun `서버 없이도 길 안내와 안내 취소를 알아듣고 경고 질문보다 뒤에 본다`() {
        assertEquals(OfflineIntent.NAVIGATE, OfflineIntents.detect("정비소 안내해줘"))
        assertEquals(OfflineIntent.NAVIGATE, OfflineIntents.detect("주유소 들를래"))
        assertEquals(OfflineIntent.NAVIGATE, OfflineIntents.detect("타이어점 안내해줘"))
        assertEquals(OfflineIntent.CANCEL_NAV, OfflineIntents.detect("안내 취소해줘"))
        assertEquals("경고 질문이 먼저다", OfflineIntent.WARNINGS, OfflineIntents.detect("이 경고등 뭐야? 정비소 안내해줘"))
        assertEquals(OfflineIntent.PART, OfflineIntents.detect("타이어 공기압 어때?"))
        assertEquals(OfflineIntent.BATTLE, OfflineIntents.detect("대전하자"))
    }
}
