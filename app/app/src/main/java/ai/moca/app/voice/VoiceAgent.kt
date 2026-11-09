package ai.moca.app.voice

import org.json.JSONArray
import org.json.JSONObject

/** LLM 호출 경계. 실제 구현은 [ai.moca.app.net.ApiClient], 테스트는 가짜 구현. */
interface ChatClient {
    suspend fun chat(body: JSONObject): ChatResult
}

sealed class ChatResult {
    data class Ok(val message: JSONObject) : ChatResult()
    data object RateLimited : ChatResult()
    data object UpstreamError : ChatResult()
    data object Timeout : ChatResult()
    data object NoConsent : ChatResult()
}

data class Answer(
    val text: String,
    val toolCalls: List<String>,
    val usedOffline: Boolean,
    val fillerSpoken: Boolean,
)

/**
 * 보이스 루프. 순수 로직이라 LLM 없이 테스트한다.
 *
 * 흐름(계획 9장):
 *  1. /chat 호출
 *  2. tool_calls 가 있으면 첫 호출 때 필러를 1회 말한다
 *  3. 도구를 실행해 tool 메시지로 붙이고 다시 /chat (최대 4회)
 *  4. 최종 답을 돌려준다
 *  5. 서버 실패·429·동의 거부 → 오프라인 의도 6개로 전환
 */
class VoiceAgent(
    private val client: ChatClient,
    private val tools: Tools,
    private val toolsJson: JSONArray,
    private val consentCloud: Boolean,
    private val onFiller: (String) -> Unit = {},
) {

    suspend fun ask(question: String, drivingMode: Boolean): Answer {
        if (!consentCloud) return offline(question, drivingMode)

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt(drivingMode)))
            put(JSONObject().put("role", "user").put("content", question))
        }

        var fillerSpoken = false
        val calledTools = mutableListOf<String>()
        var hops = 0

        while (hops < MAX_HOPS) {
            hops++
            val body = JSONObject().apply {
                put("messages", messages)
                put("tools", toolsJson)
            }
            val result = client.chat(body)
            when (result) {
                is ChatResult.Ok -> Unit
                ChatResult.RateLimited, ChatResult.UpstreamError, ChatResult.Timeout, ChatResult.NoConsent ->
                    return offline(question, drivingMode)
            }
            val message = (result as ChatResult.Ok).message

            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls == null || toolCalls.length() == 0) {
                // Hive(추론형)는 content 가 비고 reasoning_content 에 답을 넣기도 한다.
                val text = message.optString("content").ifBlank {
                    message.optString("reasoning_content").ifBlank { "확인했어요." }
                }
                return Answer(text, calledTools, usedOffline = false, fillerSpoken = fillerSpoken)
            }

            if (!fillerSpoken) {
                fillerSpoken = true
                onFiller(ai.moca.app.FILLER_TEXT)
            }

            messages.put(
                JSONObject().apply {
                    put("role", "assistant")
                    put("content", JSONObject.NULL)
                    put("tool_calls", toolCalls)
                },
            )

            for (i in 0 until toolCalls.length()) {
                val call = toolCalls.getJSONObject(i)
                val id = call.optString("id")
                val fn = call.optJSONObject("function")
                val name = fn?.optString("name").orEmpty()
                val argsText = fn?.optString("arguments").orEmpty()
                val args = runCatching { JSONObject(argsText) }.getOrNull()
                calledTools += name
                val output = tools.execute(name, args)
                messages.put(
                    JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", id)
                        put("content", output)
                    },
                )
            }
        }

        // 4회를 넘기면 지금까지의 도구 결과로 오프라인 템플릿을 쓴다.
        return offline(question, drivingMode).copy(
            toolCalls = calledTools,
            fillerSpoken = fillerSpoken,
        )
    }

    private suspend fun offline(question: String, drivingMode: Boolean): Answer {
        val intent = OfflineIntents.detect(question)
            ?: return Answer(OfflineIntents.NO_MATCH, emptyList(), usedOffline = true, fillerSpoken = false)
        val name = OfflineIntents.toolName(intent)
        val json = tools.execute(name, null)
        return Answer(
            OfflineIntents.template(intent, json, drivingMode),
            listOf(name),
            usedOffline = true,
            fillerSpoken = false,
        )
    }

    companion object {
        const val MAX_HOPS = 4

        /** 계획 9장 원문. 문구를 바꾸지 않는다. */
        val SYSTEM_PROMPT = """
            너는 MOCA의 차량 관리 비서다. 한국어 해요체로 답한다.
            - 차량 상태는 반드시 도구로 확인한 값만 말한다. 확인하지 않은 값은 추측하지 말고 도구를 호출한다.
            - 고장을 단정하지 않는다. 원인 추정은 "~일 수 있어요"로 말하고 점검을 권한다.
            - 빨간 경고등이 하나라도 있으면 첫 문장에 "안전한 곳에 정차한 뒤 점검을 받으세요"를 말한다.
            - drivingMode=true면 2문장 이내, 아니면 4문장 이내로 답한다.
            - 데모 데이터면 "데모 데이터 기준으로"라고 밝힌다.
            - 차와 관계없는 질문은 한 문장으로 정중히 거절하고 차량 관련 도움을 제안한다.
            - 숫자는 도구 결과의 값을 그대로 쓴다.
        """.trimIndent()

        private fun systemPrompt(drivingMode: Boolean): String =
            SYSTEM_PROMPT + "\ndrivingMode=" + drivingMode
    }
}
