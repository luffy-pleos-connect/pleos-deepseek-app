package ai.moca.app.net

import ai.moca.app.BuildConfig
import ai.moca.app.data.Prefs
import ai.moca.app.voice.ChatClient
import ai.moca.app.voice.ChatResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import moca.core.Category
import moca.core.Evaluation
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 서버 `/chat` 호출. 외부 라이브러리 없이 HttpURLConnection 만 쓴다.
 * - `/chat`·`/opponent` 에는 기기 비밀값을 보내지 않는다(기기 ID만). 비밀값은 `/snapshots` 에만 쓴다.
 * - 로그에 본문·키·기기 ID 원문을 남기지 않는다.
 */
class ApiClient(
    private val prefs: Prefs,
    private val baseUrl: String = BuildConfig.SERVER_BASE_URL,
) : ChatClient {

    override suspend fun chat(body: JSONObject): ChatResult = withContext(Dispatchers.IO) {
        // 서버 주소가 없으면 어디에도 보내지 않는다. 호출한 쪽은 기기 안의 답으로 넘어간다.
        if (baseUrl.isBlank()) return@withContext ChatResult.UpstreamError
        var conn: HttpURLConnection? = null
        try {
            android.util.Log.i("MOCA_NET", "POST " + baseUrl + "/chat")
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
            android.util.Log.e("MOCA_NET", "chat failed", e)
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
        if (baseUrl.isBlank()) return@withContext null
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$baseUrl/opponent?deviceId=${prefs.deviceId}")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = OPPONENT_TIMEOUT_MS
                readTimeout = OPPONENT_TIMEOUT_MS
            }
            if (conn.responseCode != 200) {
                android.util.Log.w("MOCA_NET", "opponent ${'$'}{conn.responseCode}")
                return@withContext null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            runCatching { JSONObject(text) }.getOrNull()
        } catch (e: Exception) {
            android.util.Log.e("MOCA_NET", "opponent failed", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 점검 결과 요약을 올린다. 다른 기기의 대전 상대 풀에 들어간다(데모 요약은 서버가 풀에서 뺀다). */
    suspend fun uploadSnapshot(body: JSONObject): Boolean =
        snapshots("POST", "$baseUrl/snapshots", prefs.deviceSecret, body)

    /** 서버에 올린 내 요약을 지운다. 로컬 값을 지우기 전에 읽어 둔 기기 ID·비밀값을 받는다. */
    suspend fun deleteSnapshot(deviceId: String, deviceSecret: String): Boolean =
        snapshots("DELETE", "$baseUrl/snapshots?deviceId=$deviceId", deviceSecret, null)

    private suspend fun snapshots(method: String, url: String, secret: String, body: JSONObject?): Boolean =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) return@withContext false
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = CONNECT_TIMEOUT_MS
                    setRequestProperty("X-Device-Secret", secret)
                    if (body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    }
                }
                val code = conn.responseCode
                android.util.Log.i("MOCA_NET", "$method /snapshots $code")
                code in 200..299
            } catch (e: Exception) {
                android.util.Log.e("MOCA_NET", "$method /snapshots failed", e)
                false
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

/** 화면이 닫혀도 끝까지 보내야 하는 호출(요약 올리기·지우기)이 쓰는 범위. */
val netScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** 서버 `POST /snapshots` 본문. 서버의 검증 규칙(server/validate.mjs)과 같은 모양이다. */
object SnapshotPayload {
    /** 등급이 나오지 않은 점검은 올릴 것이 없다(null). 점수는 화면에 보이는 종합 점수다. */
    fun build(deviceId: String, nickname: String, evaluation: Evaluation?, isDemo: Boolean, inspectedAt: Long): JSONObject? {
        val grade = evaluation?.grade ?: return null
        val total = evaluation.displayedOverall ?: return null
        fun score(category: Category): Any = evaluation.categories[category] ?: JSONObject.NULL
        return JSONObject().apply {
            put("deviceId", deviceId)
            put("nickname", nickname)
            put("grade", grade.label)
            put("total", total)
            put(
                "categories",
                JSONObject().apply {
                    put("power", score(Category.POWER))
                    put("brake", score(Category.BRAKE_STEERING))
                    put("tire", score(Category.TIRE))
                    put("electric", score(Category.ELECTRIC))
                    put("safety", score(Category.SAFETY))
                },
            )
            put("isDemo", isDemo)
            put("inspectedAt", inspectedAt)
        }
    }
}

/** 서버 메시지에서 tool_calls 만 꺼내는 편의 함수(테스트·디버그용). */
fun JSONObject.toolCalls(): JSONArray? = optJSONArray("tool_calls")
