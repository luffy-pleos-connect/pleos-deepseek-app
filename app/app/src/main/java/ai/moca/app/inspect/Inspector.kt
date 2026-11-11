package ai.moca.app.inspect

import ai.moca.app.MocaApp
import ai.moca.app.score.ScoreBridge
import ai.moca.app.vehicle.ItemReading
import ai.moca.app.vehicle.WarningSummary
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moca.core.Evaluation
import moca.core.InspectionHistory
import moca.core.Profile
import moca.core.VehicleSnapshot
import java.time.LocalDate

/**
 * 같은 프로세스에서 점검을 다시 돌릴지 정한다.
 * 데모 여부, 데모 시나리오 번호, 오늘 날짜가 모두 같으면 기록을 또 넣지 않는다.
 */
data class InspectKey(
    val demo: Boolean,
    val scenarioId: String,
    val day: LocalDate,
)

object InspectPolicy {
    fun shouldRun(previous: InspectKey?, now: InspectKey): Boolean = previous != now
}

/** 가장 최근 점검. 화면은 이 값을 읽고, 점검 도중에는 아직 없다. */
data class InspectionResult(
    val readings: Map<String, ItemReading>,
    val warnings: WarningSummary,
    val engine: VehicleSnapshot,
    val evaluation: Evaluation?,
    val demo: Boolean,
)

/**
 * 차량 값을 읽고, 기록 한 줄을 추가하고, 평가한다.
 * 화면 코드는 없다.
 */
class Inspector(private val app: MocaApp) {

    /**
     * @param force true 면 키가 같아도 값을 다시 읽는다(음성 도구).
     * 같은 날 기록 줄은 force 일 때 추가하지 않는다.
     */
    suspend fun run(force: Boolean): InspectionResult = gate.withLock {
        val key = currentKey(app)
        val cached = app.latestInspection
        if (!force && cached != null && !InspectPolicy.shouldRun(app.lastInspectKey, key)) {
            return@withLock cached
        }
        val source = app.vehicle()
        val snapshot = source.read()
        val warnings = source.cachedWarnings()
        val engine = ScoreBridge.toEngineSnapshot(snapshot.items, warnings, snapshot.isDemo)
        val today = ScoreBridge.today()
        val log = app.prefs.inspectionLog(snapshot.isDemo)
        val alreadyToday = InspectionHistory.read(log, Profile.REAL).any { it.inspectedAt == today }
        if (!force || !alreadyToday) {
            app.prefs.appendInspection(snapshot.isDemo, InspectionHistory.line(today, engine))
        }
        val evaluation = runCatching {
            ScoreBridge.evaluate(snapshot.items, warnings, snapshot.isDemo)
        }.getOrNull()
        val result = InspectionResult(
            readings = snapshot.items,
            warnings = warnings,
            engine = engine,
            evaluation = evaluation,
            demo = snapshot.isDemo,
        )
        app.latestInspection = result
        app.lastInspectKey = key
        result
    }

    companion object {
        private val gate = Mutex()

        fun currentKey(app: MocaApp): InspectKey = InspectKey(
            demo = app.prefs.demoEnabled,
            scenarioId = app.prefs.demoScenarioId,
            day = ScoreBridge.today(),
        )
    }
}
