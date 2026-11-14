package ai.moca.app.voice

import ai.moca.app.vehicle.DemoScenario
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 서버 없이 답할 때(오프라인 음성)의 답이 질문에 실제로 답하는지 본다.
 * 서버가 없는 동안에는 이 답이 사용자가 듣는 전부다. 「확인했어요」처럼 값이 없는 답은 답이 아니다.
 * 값은 차량(여기서는 데모 시나리오)에서 읽은 것만 쓴다.
 */
class OfflineAnswerTest {

    private val started = mutableListOf<String>()

    private fun tools(scenario: DemoScenario): Tools = Tools(
        latest = { latestOf(scenario) },
        isDemo = true,
        drivingLimited = { false },
        openScreen = { true },
        requestNav = { """{"ok":true,"needs_confirmation":true,"question":"안내할까요?"}""" },
        startBattle = { started += "start" },
    )

    /** 서버가 실패하면 기기 안 답으로 간다. */
    private fun ask(scenario: DemoScenario, question: String): String = runBlocking {
        val t = tools(scenario)
        val agent = VoiceAgent(
            client = object : ChatClient {
                override suspend fun chat(body: JSONObject): ChatResult = ChatResult.UpstreamError
            },
            tools = t,
            toolsJson = t.definitions(),
            onFiller = {},
        )
        val answer = agent.ask(question, drivingMode = false)
        assertTrue("오프라인 답이어야 한다", answer.usedOffline)
        answer.text
    }

    // ── 경고등: 무엇이 무슨 색으로 켜졌는지 말한다
    @Test
    fun `D3 에서 경고등을 물으면 ABS 와 타이어 공기압을 색과 함께 말한다`() {
        val text = ask(DemoScenario.D3, "이 경고등 뭐야?")
        assertTrue(text, text.substringBefore(". ").contains("정차"))
        assertTrue(text, text.contains("ABS"))
        assertTrue(text, text.contains("빨강"))
        assertTrue(text, text.contains("타이어 공기압"))
        assertTrue(text, text.contains("노랑"))
    }

    @Test
    fun `D2 는 노란 경고등만 있어 정차를 말하지 않는다`() {
        val text = ask(DemoScenario.D2, "이 경고등 뭐야?")
        assertTrue(text, text.contains("타이어 공기압"))
        assertTrue(text, text.contains("노랑"))
        assertFalse(text, text.contains("정차"))
    }

    @Test
    fun `D0 에서 경고등을 물으면 켜진 것이 없다고 한다`() {
        assertEquals("지금 켜진 경고등은 없어요.", ask(DemoScenario.D0, "이 경고등 뭐야?"))
    }

    @Test
    fun `경고등 도구 결과에 켜진 경고등 이름과 색이 들어 있고 안내 문장은 글자다`() = runBlocking {
        val obj = JSONObject(tools(DemoScenario.D3).execute("get_active_warnings", null))
        val active = obj.getJSONArray("active")
        assertEquals(2, active.length())
        assertEquals("ABS", active.getJSONObject(0).getString("name"))
        assertEquals("빨강", active.getJSONObject(0).getString("color"))
        assertEquals("타이어 공기압", active.getJSONObject(1).getString("name"))
        assertEquals("노랑", active.getJSONObject(1).getString("color"))
        // 예전에는 문자열 리소스 번호(정수)가 들어갔다.
        assertTrue(obj.get("safetyAdvice") is String)
        assertTrue(obj.getString("safetyAdvice").contains("정차"))
    }

    // ── 부품: 물어본 부품의 상태를 말한다
    @Test
    fun `엔진오일을 물으면 그 상태를 말한다`() {
        val ok = ask(DemoScenario.D0, "엔진오일 괜찮아?")
        assertTrue(ok, ok.contains("엔진오일"))
        assertTrue(ok, ok.contains("정상"))
        val low = ask(DemoScenario.D1, "엔진오일 괜찮아?")
        assertTrue(low, low.contains("엔진오일"))
        assertTrue(low, low.contains("부족"))
        assertTrue(low, low.contains("정비소"))
    }

    @Test
    fun `타이어를 물으면 저압 바퀴의 위치를 말한다`() {
        val d3 = ask(DemoScenario.D3, "타이어 어때?")
        assertTrue(d3, d3.contains("앞왼쪽"))
        val d2 = ask(DemoScenario.D2, "타이어 어때?")
        assertTrue(d2, d2.contains("앞왼쪽"))
        // 엔진 데모의 D2 는 앞왼쪽·뒤오른쪽이다(손으로 쓴 옛 값은 앞오른쪽이었다).
        assertTrue(d2, d2.contains("뒤오른쪽"))
        val d0 = ask(DemoScenario.D0, "타이어 어때?")
        assertTrue(d0, d0.contains("타이어"))
        assertTrue(d0, d0.contains("정상"))
    }

    @Test
    fun `타이어 값을 못 읽는 차는 못 읽는다고 말한다`() {
        val text = ask(DemoScenario.D6, "타이어 어때?")
        assertTrue(text, text.contains("타이어"))
        assertFalse(text, text.contains("정상"))
        assertTrue(text, text.contains("읽을 수 없") || text.contains("측정 불가"))
    }

    @Test
    fun `배터리를 물으면 잔량 숫자를 말한다`() {
        // 엔진 데모(Demo.kt)의 D0 은 배터리 80%, 연료 62% 다. 손으로 쓴 옛 값(배터리 62)을 따르지 않는다.
        val d0 = ask(DemoScenario.D0, "배터리 얼마나 남았어?")
        assertTrue(d0, d0.contains("80"))
        val d4 = ask(DemoScenario.D4, "배터리 얼마나 남았어?")
        assertTrue(d4, d4.contains("9%"))
        assertTrue(d4, d4.contains("충전"))
    }

    @Test
    fun `워셔액을 물으면 그 상태를 말한다`() {
        val low = ask(DemoScenario.D7, "워셔액 있어?")
        assertTrue(low, low.contains("워셔액"))
        assertTrue(low, low.contains("부족"))
        val ok = ask(DemoScenario.D0, "워셔액 있어?")
        assertTrue(ok, ok.contains("워셔액"))
        assertTrue(ok, ok.contains("정상"))
    }

    @Test
    fun `브레이크를 물으면 브레이크액 상태를 말한다`() {
        val text = ask(DemoScenario.D0, "브레이크 괜찮아?")
        assertTrue(text, text.contains("브레이크액"))
        assertTrue(text, text.contains("정상"))
    }

    @Test
    fun `부품을 통째로 물으면 감점이 있는 것을 말한다`() {
        val d3 = ask(DemoScenario.D3, "부품 상태 알려줘")
        assertTrue(d3, d3.contains("ABS"))
        assertTrue(d3, d3.contains("타이어"))
        val d0 = ask(DemoScenario.D0, "부품 상태 알려줘")
        assertTrue(d0, d0.contains("이상 없"))
    }

    // ── 어떤 답에도 값 없는 문장이나 없어진 화면 이름이 없다
    @Test
    fun `값 없는 답과 없어진 화면 이름을 쓰지 않는다`() {
        val questions = listOf(
            "내 차 등급 몇이야?", "이 경고등 뭐야?", "엔진오일 괜찮아?", "다음 등급까지 몇 점 남았어?",
            "타이어 어때?", "배터리 얼마나 남았어?", "워셔액 있어?", "부품 상태 알려줘", "대전 하자",
        )
        for (scenario in DemoScenario.entries) {
            for (question in questions) {
                val text = ask(scenario, question)
                assertTrue("$scenario $question", text.isNotBlank())
                assertFalse("$scenario $question: $text", text.contains("확인했어요"))
                assertFalse("$scenario $question: $text", text.contains("상태관리"))
            }
        }
    }

    // ── D3 처럼 빨간 경고등이 있으면 기기 안의 답도 정차 안내로 시작한다
    @Test
    fun `D3 에서 등급 답은 정차 안내로 시작한다`() {
        val text = ask(DemoScenario.D3, "내 차 등급 몇이야?")
        assertTrue(text, text.startsWith(Tools.STOP_SAFELY))
        assertTrue(text, text.contains("B"))
    }

    @Test
    fun `D3 에서 부품 답은 정차 안내로 시작한다`() {
        val text = ask(DemoScenario.D3, "엔진오일 괜찮아?")
        assertTrue(text, text.startsWith(Tools.STOP_SAFELY))
        assertTrue(text, text.contains("엔진오일"))
    }

    @Test
    fun `D3 에서 다음 등급 답은 정차 안내로 시작한다`() {
        val text = ask(DemoScenario.D3, "다음 등급까지 몇 점 남았어?")
        assertTrue(text, text.startsWith(Tools.STOP_SAFELY))
        assertTrue(text, text.contains("A"))
    }

    @Test
    fun `D0 에서는 정차 안내를 붙이지 않는다`() {
        val text = ask(DemoScenario.D0, "엔진오일 괜찮아?")
        assertFalse(text, text.contains("정차"))
    }

    @Test
    fun `경고등 답에는 정차 안내가 한 번만 붙는다`() {
        val text = ask(DemoScenario.D3, "이 경고등 뭐야?")
        assertEquals(1, Regex(Regex.escape(Tools.STOP_SAFELY)).findAll(text).count())
    }

    // ── 대전: 대전하기 화면만 열고(바로 시작하지 않는다), 그렇게 말한다
    @Test
    fun `대전을 말하면 대전하기 화면을 열고 시작 단추를 누르라고 답한다`() {
        started.clear()
        val text = ask(DemoScenario.D3, "대전 하자")
        assertEquals(listOf("start"), started)
        assertEquals("대전하기 화면을 열었어요. 시작 단추를 눌러 주세요.", text)
    }

    // ── 화면 이름: 지금 탭 이름으로 알아듣는다
    @Test
    fun `지금 탭 이름으로 화면을 열어 달라는 말을 알아듣는다`() {
        assertEquals(OfflineIntent.OPEN_SCREEN, OfflineIntents.detect("상세 화면 열어 줘"))
        assertEquals(OfflineIntent.OPEN_SCREEN, OfflineIntents.detect("순위 보여 줘"))
    }
}
