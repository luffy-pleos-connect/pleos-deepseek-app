package ai.moca.app.vehicle

import ai.moca.app.score.ScoreBridge
import moca.core.Demo
import moca.core.FixAction
import moca.core.Priority
import org.junit.Assert.assertEquals
import org.junit.Test

/** 데모 D7: 워셔액만 부족하다. 「휴대폰으로 부품 보기」(QR)를 데모에서 눌러 볼 수 있어야 한다. */
class DemoWasherScenarioTest {

    @Test
    fun `D7 의 가장 먼저 할 일은 워셔액이고 휴대폰으로 부품 보기로 이어진다`() {
        val scenario = DemoScenario.byId("D7")
        assertEquals("D7", scenario.id)
        val engine = ScoreBridge.toEngineSnapshot(scenario.readings(), scenario.warnings(), true)
        val top = Priority.topProblem(engine)
        assertEquals("WASHER", top?.group)
        assertEquals(FixAction.PARTS, Priority.actionFor(top!!, engine))
    }

    @Test
    fun `D7 의 등급과 종합은 코어 데모와 같다`() {
        val scenario = DemoScenario.byId("D7")
        val core = Demo.scenarios().first { it.first.id == "D7" }.first
        assertEquals(core.expectedGrade.label, scenario.expectedGrade)
        assertEquals(core.expectedDisplayed, scenario.expectedTotal)
        assertEquals(core.opponent, scenario.opponentNickname())
        val evaluation = ScoreBridge.evaluate(scenario.readings(), scenario.warnings(), true)
        assertEquals(scenario.expectedGrade, evaluation?.grade?.label)
        assertEquals(scenario.expectedTotal, evaluation?.displayedOverall)
    }
}
