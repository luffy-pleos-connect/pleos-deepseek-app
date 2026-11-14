package ai.moca.app.battle

import ai.moca.app.data.Prefs
import ai.moca.app.vehicle.DemoScenario
import moca.core.Seed
import moca.core.Seeds

/**
 * 상대 찾기. 상대는 연습 상대(core 의 Seeds)뿐이다. 실제 사용자·서버 상대는 없다.
 * - 데모 프로필: 시나리오별 고정 상대(계획 10장)
 * - 실제 프로필: 연습 상대 12명(Seeds.battlePool) 중 무작위 하나
 * 상대 평가는 호출한 쪽이 [Seed.evaluation] 으로 만든다(관리 점수·빨간 경고등 상한 포함, B-08).
 */
class BattleRepository(
    private val prefs: Prefs,
    private val random: java.util.Random = java.util.Random(),
) {

    /** [previous] 는 방금 겨룬 상대. 실제 모드는 가능하면 다른 상대를 뽑는다. */
    fun findOpponent(previous: String? = null): Seed? {
        if (prefs.demoEnabled) {
            return Seeds.byNickname(DemoScenario.byId(prefs.demoScenarioId).opponentNickname())
        }
        val pool = Seeds.battlePool
        val others = pool.filter { it.nickname != previous }.ifEmpty { pool }
        if (others.isEmpty()) return null
        return others[random.nextInt(others.size)]
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
