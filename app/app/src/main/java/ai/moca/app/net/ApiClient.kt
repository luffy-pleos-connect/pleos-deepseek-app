package ai.moca.app.net

import ai.moca.app.BuildConfig
import ai.moca.app.data.Prefs
import ai.moca.app.voice.ChatClient
import ai.moca.app.voice.ChatResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 서버 `/chat` 호출. 외부 라이브러리 없이 HttpURLConnection 만 쓴다.
 * - 기기 비밀값은 보내지 않는다(기기 ID만 헤더로).
 * - 로그에 본문·키·기기 ID 원문을 남기지 않는다.
 */
class ApiClient(
    private val prefs: Prefs,
    private val baseUrl: String = BuildConfig.SERVER_BASE_URL,
) : ChatClient {

    override suspend fun chat(body: JSONObject): ChatResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$baseUrl/chat")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-Device-Id", prefs.deviceId)
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

            val code = conn.responseCode
            when {
                code == 200 -> {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = runCatching { JSONObject(text) }.getOrNull()
                        ?: return@withContext ChatResult.UpstreamError
                    val message = messageOf(root) ?: return@withContext ChatResult.UpstreamError
                    ChatResult.Ok(message)
                }
                code == 429 -> ChatResult.RateLimited
                code == 504 -> ChatResult.Timeout
                else -> ChatResult.UpstreamError
            }
        } catch (e: Exception) {
            ChatResult.UpstreamError
        } finally {
            conn?.disconnect()
        }
    }

    /** 서버는 Hive 응답의 message 를 그대로 돌려준다. {message:{...}} 또는 {message:{role,content}} */
    private fun messageOf(root: JSONObject): JSONObject? {
        val m = root.opt("message")
        return when (m) {
            is JSONObject -> m
            else -> null
        }
    }

    /** 대전 상대 조회. 실패하면 null. */
    suspend fun opponent(): JSONObject? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$baseUrl/opponent?deviceId=${prefs.deviceId}")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = OPPONENT_TIMEOUT_MS
                readTimeout = OPPONENT_TIMEOUT_MS
            }
            if (conn.responseCode != 200) return@withContext null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            runCatching { JSONObject(text) }.getOrNull()
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 20_000
        const val OPPONENT_TIMEOUT_MS = 3_000
    }
}

/** 서버 메시지에서 tool_calls 만 꺼내는 편의 함수(테스트·디버그용). */
fun JSONObject.toolCalls(): JSONArray? = optJSONArray("tool_calls")
