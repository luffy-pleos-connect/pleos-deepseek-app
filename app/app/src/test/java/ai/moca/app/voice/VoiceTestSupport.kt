package ai.moca.app.voice

import ai.moca.app.inspect.InspectionResult
import ai.moca.app.score.ScoreBridge
import ai.moca.app.vehicle.DemoScenario
import moca.core.Demo
import moca.core.Rules
import moca.core.Scoring

/** 데모 시나리오의 «가장 최근 점검». 도구는 차를 다시 읽지 않고 이 값만 쓴다. */
internal fun latestOf(scenario: DemoScenario): InspectionResult {
    val engine = Demo.snapshotOf(scenario.id)
    return InspectionResult(
        readings = ScoreBridge.toReadings(engine),
        warnings = ScoreBridge.toWarnings(engine, isDemo = true),
        engine = engine,
        evaluation = Scoring.evaluate(engine, Demo.CARE_TODAY, Rules.hasRed(engine)),
        demo = true,
        atMillis = 1_700_000_000_000L,
        distanceDriven = null,
    )
}
