package ai.moca.app.voice

import ai.moca.app.vehicle.DemoScenario
import ai.moca.app.vehicle.ItemReading
import ai.moca.app.vehicle.VehicleSnapshot
import ai.moca.app.vehicle.VehicleSource
import ai.moca.app.vehicle.WarningSummary
import moca.core.Evaluation
import moca.core.FixAction
import moca.core.Scoring
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * AT-08: 보이스 루프(가짜 LLM). 도구 실행, 필러 1회, 4회 상한, 도구 오류,
 * 서버 오류 → 오프라인 전환, 오프라인 의도 6개.
 */
class VoiceAgentTest {

    // ── 가짜 차량
    private class FakeVehicle(private val scenario: DemoScenario) : VehicleSource {
        override suspend fun read(): VehicleSnapshot =
            VehicleSnapshot(scenario.readings(), 1_700_000_000_000L, isDemo = true)

        override fun cachedWarnings(): WarningSummary = scenario.warnings()
        override fun startWatchingWarnings(onChange: (WarningSummary) -> Unit) = Unit
        override fun stopWatchingWarnings() = Unit
    }

    private fun buildTools(
        scenario: DemoScenario = DemoScenario.D3,
        driving: Boolean = false,
        screens: MutableList<String> = mutableListOf(),
        startFix: suspend (FixAction) -> String = { """{"ok":true,"result":"NAVI_OPENED"}""" },
        starts: MutableList<String> = mutableListOf(),
    ): Tools {
        val vehicle = FakeVehicle(scenario)
        return Tools(
            vehicle = vehicle,
            isDemo = true,
            drivingLimited = { driving },
            openScreen = { s -> screens += s; true },
            evaluate = { readings, warnings, isDemo -> evaluate(readings, warnings, isDemo) },
            startFix = startFix,
            startBattle = { starts += "start" },
        )
    }

    private fun evaluate(
        readings: Map<String, ItemReading>,
        warnings: WarningSummary,
        isDemo: Boolean,
    ): Evaluation? = ai.moca.app.score.ScoreBridge.evaluate(readings, warnings, isDemo)

    // ── 가짜 LLM: 미리 정한 응답을 순서대로 돌려준다
    private class ScriptedClient(private val responses: List<ChatResult>) : ChatClient {
        val bodies = mutableListOf<JSONObject>()
        override suspend fun chat(body: JSONObject): ChatResult {
            bodies += body
            return responses.getOrElse(bodies.size - 1) { responses.last() }
        }
    }

    private fun toolCallMessage(vararg calls: Pair<String, String>): JSONObject {
        val arr = JSONArray()
        calls.forEachIndexed { i, (name, args) ->
            arr.put(
                JSONObject().apply {
                    put("id", "call_$i")
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", name)
                        put("arguments", args)
                    })
                },
            )
        }
        return JSONObject().apply {
            put("role", "assistant")
            put("content", JSONObject.NULL)
            put("tool_calls", arr)
        }
    }

    private fun textMessage(text: String) = JSONObject().apply {
        put("role", "assistant")
        put("content", text)
    }

    private fun agent(
        client: ChatClient,
        tools: Tools = buildTools(),
        consent: Boolean = true,
        fillers: MutableList<String> = mutableListOf(),
    ): VoiceAgent = VoiceAgent(
        client = client,
        tools = tools,
        toolsJson = tools.definitions(),
        consentCloud = consent,
        onFiller = { fillers += it },
    )

    // ── 1. 도구 실행 → tool 메시지 → 최종 답
    @Test
    fun `도구 호출이 오면 실행하고 tool 메시지를 붙여 다시 부른다`() = runBlocking {
        val client = ScriptedClient(
            listOf(
                ChatResult.Ok(toolCallMessage("get_latest_inspection" to "{}")),
                ChatResult.Ok(textMessage("지금 등급은 B예요.")),
            ),
        )
        val answer = agent(client).ask("내 차 등급 몇이야?", drivingMode = false)
        // 기본 시나리오 D3 는 빨간 경고등이 있어 정차 안내가 앞에 붙는다.
        assertEquals("${Tools.STOP_SAFELY}. 지금 등급은 B예요.", answer.text)
        assertEquals(listOf("get_latest_inspection"), answer.toolCalls)
        assertFalse(answer.usedOffline)
        assertEquals(2, client.bodies.size)
        val second = client.bodies[1].getJSONArray("messages")
        val last = second.getJSONObject(second.length() - 1)
        assertEquals("tool", last.getString("role"))
        assertTrue(last.getString("content").contains("\"ok\":true"))
    }

    // ── 2. 필러 정확히 1회
    @Test
    fun `필러는 첫 도구 호출 때 한 번만 말한다`() = runBlocking {
        val fillers = mutableListOf<String>()
        val client = ScriptedClient(
            listOf(
                ChatResult.Ok(toolCallMessage("get_active_warnings" to "{}")),
                ChatResult.Ok(toolCallMessage("get_latest_inspection" to "{}")),
                ChatResult.Ok(textMessage("경고 2개예요.")),
            ),
        )
        val answer = agent(client, fillers = fillers).ask("경고등 뭐야?", drivingMode = false)
        assertEquals(listOf(ai.moca.app.FILLER_TEXT), fillers)
        assertTrue(answer.fillerSpoken)
        assertEquals(2, answer.toolCalls.size)
    }

    // ── 3. 4회 상한
    @Test
    fun `도구 호출이 계속되면 4회에서 멈춘다`() = runBlocking {
        val loop = ChatResult.Ok(toolCallMessage("get_latest_inspection" to "{}"))
        val client = ScriptedClient(List(6) { loop })
        val answer = agent(client).ask("내 차 등급 몇이야?", drivingMode = false)
        assertEquals(VoiceAgent.MAX_HOPS, client.bodies.size)
        assertTrue(answer.usedOffline)
    }

    // ── 4. 도구 오류 처리
    @Test
    fun `알 수 없는 도구는 오류 JSON 을 돌려주고 루프는 계속된다`() = runBlocking {
        val client = ScriptedClient(
            listOf(
                ChatResult.Ok(toolCallMessage("no_such_tool" to "{}")),
                ChatResult.Ok(textMessage("확인했어요.")),
            ),
        )
        val answer = agent(client).ask("이상한 도구 불러 줘", drivingMode = false)
        // 기본 시나리오 D3 는 빨간 경고등이 있어 정차 안내가 앞에 붙는다.
        assertEquals("${Tools.STOP_SAFELY}. 확인했어요.", answer.text)
        val msgs = client.bodies[1].getJSONArray("messages")
        val toolMsg = msgs.getJSONObject(msgs.length() - 1)
        assertTrue(toolMsg.getString("content").contains("unknown_tool"))
    }

    // ── 5. 서버 오류 → 오프라인
    @Test
    fun `서버 오류면 오프라인 의도로 답한다`() = runBlocking {
        val client = ScriptedClient(listOf(ChatResult.UpstreamError))
        val answer = agent(client).ask("내 차 등급 몇이야?", drivingMode = false)
        assertTrue(answer.usedOffline)
        assertTrue(answer.text.contains("등급"))
        assertEquals(listOf("get_latest_inspection"), answer.toolCalls)
    }

    // ── 6. 429 → 오프라인
    @Test
    fun `429 면 오프라인으로 전환한다`() = runBlocking {
        val client = ScriptedClient(listOf(ChatResult.RateLimited))
        val answer = agent(client).ask("경고등 뭐야?", drivingMode = false)
        assertTrue(answer.usedOffline)
        assertTrue(answer.text.contains("경고등"))
    }

    // ── 7. 동의 거부 → 처음부터 오프라인
    @Test
    fun `클라우드 동의가 없으면 LLM 을 부르지 않는다`() = runBlocking {
        val client = ScriptedClient(listOf(ChatResult.Ok(textMessage("불려서는 안 됨"))))
        val answer = agent(client, consent = false).ask("점검해 줘", drivingMode = false)
        assertTrue(answer.usedOffline)
        assertEquals(0, client.bodies.size)
        assertEquals(listOf("run_inspection"), answer.toolCalls)
    }

    // ── 8~13. 오프라인 의도 6개
    @Test
    fun `오프라인 의도 6개가 각각 맞는 도구를 부른다`() = runBlocking {
        val cases = mapOf(
            "점검해 줘" to "run_inspection",
            "내 등급 몇이야" to "get_latest_inspection",
            "빨간 경고등 켜졌어" to "get_active_warnings",
            "엔진오일 괜찮아" to "get_part_status",
            "대전 시작해 줘" to "start_battle",
            "홈으로 가 줘" to "open_screen",
        )
        for ((question, expected) in cases) {
            val answer = agent(ScriptedClient(listOf(ChatResult.UpstreamError)), consent = false)
                .ask(question, drivingMode = false)
            assertEquals("질문: $question", listOf(expected), answer.toolCalls)
            assertTrue("질문: $question 는 오프라인이어야 한다", answer.usedOffline)
        }
    }

    // ── 14. 오프라인 매칭 실패
    @Test
    fun `오프라인에서 매칭 실패하면 안내 문구를 준다`() = runBlocking {
        val answer = agent(ScriptedClient(listOf(ChatResult.UpstreamError)), consent = false)
            .ask("오늘 날씨 어때?", drivingMode = false)
        assertTrue(answer.usedOffline)
        assertTrue(answer.text.contains("이해하지 못했"))
    }

    // ── 15. 주행 중 2문장 제한
    @Test
    fun `주행 중이면 오프라인 답을 2문장으로 자른다`() {
        val long = "첫 문장이에요. 둘째 문장이에요. 셋째 문장이에요. 넷째 문장이에요."
        val limited = OfflineIntents.limitSentences(long, 2)
        assertEquals(2, limited.split(".").count { it.isNotBlank() })
    }

    // ── 16. 도구 결과에 키·VIN 없음
    @Test
    fun `도구 결과 JSON 에 키나 VIN 이 없다`() = runBlocking {
        val tools = buildTools()
        val outputs = listOf(
            tools.execute("get_latest_inspection", null),
            tools.execute("get_active_warnings", null),
            tools.execute("get_part_status", null),
            tools.execute("get_energy_and_distance", null),
        )
        for (out in outputs) {
            assertFalse(out.contains("HIVE_API_KEY"))
            assertFalse(out.lowercase().contains("vin"))
            assertFalse(out.contains("deviceSecret"))
        }
    }

    // ── 17. 정차면 대전을 한 번 시작하고, 주행 중이면 화면을 열지 않는다
    @Test
    fun `정차하면 start_battle 이 대전을 한 번 시작한다`() = runBlocking {
        val starts = mutableListOf<String>()
        val out = JSONObject(buildTools(starts = starts).execute("start_battle", null))
        assertTrue(out.getBoolean("ok"))
        assertEquals(listOf("start"), starts)
    }

    @Test
    fun `주행 중이면 start_battle 이 대전을 시작하지 않는다`() = runBlocking {
        val starts = mutableListOf<String>()
        val out = JSONObject(buildTools(driving = true, starts = starts).execute("start_battle", null))
        assertFalse(out.getBoolean("ok"))
        assertEquals("driving_limited", out.getString("reason"))
        assertTrue(starts.isEmpty())
    }

    // ── 18. 주행 중 화면 열기는 거부된다
    @Test
    fun `주행 중이면 open_screen 이 거부된다`() = runBlocking {
        val screens = mutableListOf<String>()
        val tools = buildTools(driving = true, screens = screens)
        val out = tools.execute("open_screen", JSONObject().put("screen", "status"))
        assertTrue(out.contains("driving_limited"))
        assertTrue(screens.isEmpty())
    }

    // ── 19. D3 경고 요약이 도구 결과에 반영된다
    @Test
    fun `D3 경고 도구는 빨강 1 노랑 1 을 돌려준다`() = runBlocking {
        val out = buildTools(DemoScenario.D3).execute("get_active_warnings", null)
        val obj = JSONObject(out)
        assertEquals(1, obj.getInt("red"))
        assertEquals(1, obj.getInt("yellow"))
        assertEquals(2, obj.getInt("total"))
    }

    // ── 20. 도구 결과가 없을 때(측정 불가) 정직하게 답한다
    @Test
    fun `측정 불가 차량이면 등급 대신 점검을 권한다`() = runBlocking {
        val tools = Tools(
            vehicle = object : VehicleSource {
                override suspend fun read(): VehicleSnapshot =
                    VehicleSnapshot(emptyMap(), 1_700_000_000_000L, isDemo = false)
                override fun cachedWarnings(): WarningSummary = WarningSummary.empty()
                override fun startWatchingWarnings(onChange: (WarningSummary) -> Unit) = Unit
                override fun stopWatchingWarnings() = Unit
            },
            isDemo = false,
            drivingLimited = { false },
            openScreen = { true },
            evaluate = { readings, warnings, isDemo ->
                ai.moca.app.score.ScoreBridge.evaluate(readings, warnings, isDemo)
            },
            startFix = { """{"ok":true,"result":"NAVI_OPENED"}""" },
        )
        val answer = VoiceAgent(
            client = ScriptedClient(listOf(ChatResult.UpstreamError)),
            tools = tools,
            toolsJson = tools.definitions(),
            consentCloud = false,
        ).ask("내 차 등급 몇이야?", drivingMode = false)
        assertTrue(answer.usedOffline)
        assertTrue("점검을 권해야 한다: ${answer.text}", answer.text.contains("점검"))
        assertFalse("없는 등급을 지어내면 안 된다", answer.text.contains("등급은"))
    }

    // ── 21. 추론형 응답 폴백
    @Test
    fun `content 가 비면 추론문을 보여 주지 않고 기기 안의 답을 쓴다`() = runBlocking {
        val msg = JSONObject().apply {
            put("role", "assistant")
            put("content", "")
            put("reasoning_content", "We need answer Korean one sentence.")
        }
        val answer = agent(ScriptedClient(listOf(ChatResult.Ok(msg)))).ask("내 차 등급 몇이야?", drivingMode = false)
        assertFalse(answer.text.contains("We need"))
        assertTrue(answer.text, answer.text.contains("B"))
        assertTrue(answer.usedOffline)
    }

    @Test
    fun `빨간 경고등이 있는데 모델이 정차 안내를 빼먹으면 첫 문장에 붙인다`() = runBlocking {
        val client = ScriptedClient(listOf(ChatResult.Ok(textMessage("엔진오일 상태는 정상이에요."))))
        val answer = agent(client).askFast("엔진오일 괜찮아?", drivingMode = false)
        assertEquals("${Tools.STOP_SAFELY}. 엔진오일 상태는 정상이에요.", answer.text)
        assertFalse(answer.usedOffline)
    }

    @Test
    fun `모델이 이미 정차를 안내했거나 빨간 경고등이 없으면 답을 그대로 둔다`() = runBlocking {
        val said = "안전한 곳에 정차한 뒤 점검을 받으세요. ABS 경고등이 켜져 있어요."
        val red = agent(ScriptedClient(listOf(ChatResult.Ok(textMessage(said))))).askFast("경고등 뭐야?", drivingMode = false)
        assertEquals(said, red.text)
        val clean = agent(ScriptedClient(listOf(ChatResult.Ok(textMessage("엔진오일 상태는 정상이에요.")))), tools = buildTools(DemoScenario.D0))
            .askFast("엔진오일 괜찮아?", drivingMode = false)
        assertEquals("엔진오일 상태는 정상이에요.", clean.text)
    }

    // ── 22. 빠른 경로: 도구를 먼저 실행하고 1회만 부른다
    @Test
    fun `빠른 경로는 도구를 선실행하고 LLM 을 한 번만 부른다`() = runBlocking {
        val client = ScriptedClient(listOf(ChatResult.Ok(textMessage("경고 2개예요."))))
        val answer = agent(client).askFast("경고등 뭐야?", drivingMode = false)
        assertEquals(1, client.bodies.size)
        // 기본 시나리오 D3 는 빨간 경고등이 있어 정차 안내가 앞에 붙는다.
        assertEquals("${Tools.STOP_SAFELY}. 경고 2개예요.", answer.text)
        assertFalse(answer.usedOffline)
        val msgs = client.bodies[0].getJSONArray("messages")
        val combined = (0 until msgs.length()).joinToString("|") { msgs.getJSONObject(it).optString("content") }
        assertTrue("도구 결과가 프롬프트에 들어가야 한다", combined.contains("\"total\":2"))
        assertTrue("도구 재호출 금지 지시가 있어야 한다", combined.contains("도구를 다시 부르지 않는다"))
    }

    @Test
    fun `빠른 경로에서 모델이 도구를 또 부르면 일반 경로로 되돌린다`() = runBlocking {
        val client = ScriptedClient(
            listOf(
                ChatResult.Ok(toolCallMessage("get_latest_inspection" to "{}")),
                ChatResult.Ok(textMessage("일반 경로 답")),
            ),
        )
        val answer = agent(client).askFast("경고등 뭐야?", drivingMode = false)
        assertTrue("2회 이상 호출돼야 한다", client.bodies.size >= 2)
        assertEquals("일반 경로 답", answer.text)
    }

    @Test
    fun `빠른 경로에서 의도를 못 찾으면 일반 경로로 간다`() = runBlocking {
        val client = ScriptedClient(listOf(ChatResult.Ok(textMessage("일반 답"))))
        val answer = agent(client).askFast("오늘 날씨 어때?", drivingMode = false)
        assertEquals("일반 답", answer.text)
    }

    // ── 23. 도구 정의 8개, 이름 중복 없음
    @Test
    fun `도구 정의는 8개이고 이름이 유일하다`() {
        val defs = buildTools().definitions()
        assertEquals(8, defs.length())
        val names = (0 until defs.length()).map {
            defs.getJSONObject(it).getJSONObject("function").getString("name")
        }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.contains("start_navigation_to_service"))
    }

    @Test
    fun `정비소 안내 도구는 startFix 의 JSON 을 그대로 돌려준다`() = runBlocking {
        val seen = mutableListOf<FixAction>()
        val json = """{"ok":true,"result":"NAVI_OPENED","message":"내비게이션 검색을 열었어요"}"""
        val tools = buildTools(startFix = { action ->
            seen += action
            json
        })
        val out = tools.execute("start_navigation_to_service", null)
        assertEquals(listOf(FixAction.REPAIR_SHOP), seen)
        assertEquals(json, out)
        assertFalse(out.contains("not_implemented"))
    }

    @Test
    fun `정비소 안내 도구는 실패 JSON 도 그대로 전달한다`() = runBlocking {
        val json = """{"ok":false,"result":"FAILED","message":"내비게이션에서 직접 찾아 주세요"}"""
        val out = buildTools(startFix = { json }).execute("start_navigation_to_service", null)
        val obj = JSONObject(out)
        assertFalse(obj.getBoolean("ok"))
        assertEquals("FAILED", obj.getString("result"))
    }

    @Test
    fun `D7 에서 등급 경고 부품 대전 답이 예외 없이 나온다`() = runBlocking {
        val tools = buildTools(DemoScenario.D7)
        val grade = tools.execute("get_latest_inspection", null)
        val warnings = tools.execute("get_active_warnings", null)
        val part = tools.execute("get_part_status", null)
        val battle = tools.execute("start_battle", null)
        val gradeText = OfflineIntents.template(OfflineIntent.GRADE, grade, false)
        val warningText = OfflineIntents.template(OfflineIntent.WARNINGS, warnings, false)
        val partText = OfflineIntents.template(OfflineIntent.PART, part, false)
        val battleText = OfflineIntents.template(OfflineIntent.BATTLE, battle, false)
        assertTrue(gradeText.contains("A"))
        assertTrue(gradeText.contains("84"))
        assertTrue(warningText.isNotEmpty())
        assertTrue(partText.isNotEmpty())
        assertTrue(battleText.isNotEmpty())
    }
}
