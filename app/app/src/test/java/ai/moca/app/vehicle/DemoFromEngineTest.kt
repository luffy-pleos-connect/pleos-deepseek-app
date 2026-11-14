package ai.moca.app.vehicle

import ai.moca.app.score.ScoreBridge
import kotlinx.coroutines.runBlocking
import moca.core.Demo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 개발 문서 W2(B-10): 데모는 엔진의 데모 데이터를 그대로 쓴다. 손으로 쓴 숫자 묶음을 두지 않는다.
 * lead 가 쓴 테스트다. 워커는 이 파일을 고치지 않는다.
 *
 * 약속:
 *   ScoreBridge.toReadings(snapshot: moca.core.VehicleSnapshot): Map<String, ItemReading>
 *   ScoreBridge.toWarnings(snapshot: moca.core.VehicleSnapshot, isDemo: Boolean): WarningSummary
 *   두 결과를 ScoreBridge.toEngineSnapshot 에 다시 넣으면 처음 스냅샷과 같다(화면이 읽기 값으로 그리는 동안 필요하다).
 */
class DemoFromEngineTest {

    private val ids = listOf("D0", "D1", "D2", "D3", "D4", "D5", "D6", "D7")

    @Test
    fun `엔진 스냅샷을 읽기 값으로 바꿨다가 되돌리면 같은 스냅샷이다`() {
        for (id in ids) {
            val snapshot = Demo.snapshotOf(id)
            val back = ScoreBridge.toEngineSnapshot(
                ScoreBridge.toReadings(snapshot),
                ScoreBridge.toWarnings(snapshot, isDemo = true),
                isDemo = true,
            )
            assertEquals(id, snapshot, back)
        }
    }

    @Test
    fun `데모 차량 소스는 엔진 데모 스냅샷에서 나온 값을 준다`() = runBlocking {
        for (id in ids) {
            val source = DemoScenarios.source(id)
            val read = source.read()
            assertTrue(read.isDemo)
            val engine = ScoreBridge.toEngineSnapshot(read.items, source.cachedWarnings(), isDemo = true)
            assertEquals(id, Demo.snapshotOf(id), engine)
        }
    }

    @Test
    fun `데모 D3 의 경고 배지는 빨강이고 두 개다`() {
        val warnings = DemoScenarios.source("D3").cachedWarnings()
        assertEquals(1, warnings.redCount)
        assertEquals(1, warnings.yellowCount)
        assertEquals(2, warnings.total)
        assertTrue(warnings.isDemo)
        assertEquals(setOf("ABS", "TPMS"), warnings.active.map { it.type }.toSet())
    }

    @Test
    fun `데모 D0 은 경고가 없다`() {
        assertEquals(0, DemoScenarios.source("D0").cachedWarnings().total)
    }

    @Test
    fun `데모 고정 상대 이름은 엔진 데모 데이터와 같다`() {
        for (id in ids) {
            val expected = Demo.scenarios().first { it.first.id == id }.first.opponent
            assertEquals(id, expected, DemoScenario.byId(id).opponentNickname())
        }
    }
}
