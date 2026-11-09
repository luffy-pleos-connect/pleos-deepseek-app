package ai.moca.app.battle

import ai.moca.app.data.Prefs
import ai.moca.app.net.ApiClient
import ai.moca.app.vehicle.DemoScenario
import moca.core.Category
import moca.core.Grade
import moca.core.Seeds

data class Opponent(
    val nickname: String,
    val grade: String,
    val total: Double,
    val categories: Map<Category, Int?>,
)

sealed class OpponentResult {
    data class Found(val opponent: Opponent, val isSeed: Boolean, val isPractice: Boolean) : OpponentResult()
    data class Unavailable(val reason: String) : OpponentResult()
}

interface BattleGateway {
    suspend fun findOpponent(myCategories: Map<Category, Int?>): OpponentResult
}

/**
 * 상대 찾기.
 * - 데모 프로필: 시나리오별 고정 상대(계획 10장)
 * - 실제 프로필: 서버 → 3초 안에 없으면 앱 안 시드 = "연습 상대"
 * - 실사용자 대전 동의가 없으면 처음부터 연습 상대
 */
class BattleRepository(
    private val prefs: Prefs,
    private val api: ApiClient,
    private val random: java.util.Random = java.util.Random(),
) : BattleGateway {

    override suspend fun findOpponent(myCategories: Map<Category, Int?>): OpponentResult {
        if (prefs.demoEnabled) {
            val scenario = DemoScenario.byId(prefs.demoScenarioId)
            val seed = Seeds.byNickname(scenario.opponentNickname())
            val opponent = if (seed != null) {
                Opponent(seed.nickname, seed.grade.label, seed.overall, seed.categories)
            } else {
                // 데모 전용(라이벌드라이버)은 시드 풀에 없으므로 계획 표 값으로 만든다.
                Opponent("라이벌드라이버", "A", 88.2, rivalCategories())
            }
            return OpponentResult.Found(opponent, isSeed = true, isPractice = false)
        }

        if (prefs.consentBattle) {
            val json = runCatching { api.opponent() }.getOrNull()
            if (json != null) {
                val opponent = Opponent(
                    nickname = json.optString("nickname"),
                    grade = json.optString("grade"),
                    total = json.optDouble("total", 0.0),
                    categories = parseCategories(json.optJSONObject("categories")),
                )
                if (opponent.nickname.isNotEmpty()) {
                    return OpponentResult.Found(opponent, json.optBoolean("isSeed"), isPractice = false)
                }
            }
        }

        val pool = Seeds.battlePool
        if (pool.isEmpty()) return OpponentResult.Unavailable("no_seed")
        val seed = pool[random.nextInt(pool.size)]
        return OpponentResult.Found(
            Opponent(seed.nickname, seed.grade.label, seed.overall, seed.categories),
            isSeed = true,
            isPractice = true,
        )
    }

    private fun rivalCategories(): Map<Category, Int?> = mapOf(
        Category.POWER to 100,
        Category.BRAKE_STEERING to 100,
        Category.TIRE to 100,
        Category.ELECTRIC to 90,
        Category.SAFETY to 90,
    )

    private fun parseCategories(json: org.json.JSONObject?): Map<Category, Int?> {
        if (json == null) return emptyMap()
        fun read(key: String): Int? = if (json.has(key) && !json.isNull(key)) json.optInt(key) else null
        return mapOf(
            Category.POWER to read("power"),
            Category.BRAKE_STEERING to read("brake"),
            Category.TIRE to read("tire"),
            Category.ELECTRIC to read("electric"),
            Category.SAFETY to read("safety"),
        )
    }

    /** 전적 저장. 프로필별로 나눈다. */
    fun record(win: Boolean, draw: Boolean) {
        val key = if (prefs.demoEnabled) KEY_DEMO else KEY_REAL
        val (w, d, l) = prefs.battleRecord(key)
        prefs.setBattleRecord(
            key,
            if (win) w + 1 else w,
            if (draw) d + 1 else d,
            if (!win && !draw) l + 1 else l,
        )
    }

    fun record(): Triple<Int, Int, Int> =
        prefs.battleRecord(if (prefs.demoEnabled) KEY_DEMO else KEY_REAL)

    companion object {
        const val KEY_REAL = "battle_real"
        const val KEY_DEMO = "battle_demo"
    }
}

/** 상대의 카테고리 점수로 평가를 만든다. 대전 판정은 :core Battle 이 한다. */
fun opponentEvaluation(opponent: Opponent): moca.core.Evaluation =
    moca.core.Scoring.evaluate(opponent.categories, care = 50, redCap = false)

fun gradeOf(label: String): Grade? = Grade.entries.firstOrNull { it.label == label }
