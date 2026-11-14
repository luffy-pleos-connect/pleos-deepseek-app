package ai.moca.app.inspect

import ai.moca.app.MocaApp
import ai.moca.app.data.InspectionStore
import ai.moca.app.data.StoredInspection
import ai.moca.app.score.ScoreBridge
import ai.moca.app.vehicle.ItemReading
import ai.moca.app.vehicle.PleosVehicleSource
import ai.moca.app.vehicle.WarningSummary
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moca.core.Evaluation
import moca.core.Profile
import moca.core.Rules
import moca.core.Scoring
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
    /** 기록을 남긴 시각. "○분 전 점검 기준" 에 쓴다. */
    val atMillis: Long,
    /** 누적 주행거리(표시용). 점수에는 쓰지 않는다. */
    val distanceDriven: Double?,
)

/**
 * 차량 값을 읽고, 기록 한 줄을 [InspectionStore] 에 남기고, 평가한다.
 * 화면 코드는 없다.
 */
class Inspector(private val app: MocaApp) {

    /**
     * @param force true 면 키가 같아도 값을 다시 읽는다(음성 도구·다시 점검).
     */
    suspend fun run(force: Boolean): InspectionResult = gate.withLock {
        val key = currentKey(app)
        val cached = app.latestInspection
        if (!force && cached != null && !InspectPolicy.shouldRun(app.lastInspectKey, key)) {
            return@withLock cached
        }
        val today = ScoreBridge.today()
        val store = app.store
        val result = if (app.prefs.demoEnabled) {
            demoRun(store, today)
        } else {
            realRun(store, today)
        }
        app.latestInspection = result
        app.lastInspectKey = key
        result
    }

    /** 데모: 저장소가 시나리오 기록을 깔고(또는 한 줄 붙이고) 그 스냅샷을 엔진 입력으로 쓴다. */
    private fun demoRun(store: InspectionStore, today: LocalDate): InspectionResult {
        val saved = store.demoInspect(app.prefs.demoScenarioId, today)
        return fromStored(store, saved, today)
    }

    /** 실제: 차를 읽은 값으로 한 줄 붙인다. force 든 아니든 실제로 읽었으면 붙인다. */
    private suspend fun realRun(store: InspectionStore, today: LocalDate): InspectionResult {
        val source = app.vehicle()
        val snapshot = source.read()
        val warnings = source.cachedWarnings()
        val engine = ScoreBridge.toEngineSnapshot(snapshot.items, warnings, isDemo = false)
        val saved = store.append(
            Profile.REAL,
            engine,
            today,
            distanceDriven = snapshot.items[PleosVehicleSource.KEY_ODOMETER]?.value,
        )
        return InspectionResult(
            readings = snapshot.items,
            warnings = warnings,
            engine = engine,
            evaluation = Scoring.evaluate(engine, store.care(Profile.REAL, today), Rules.hasRed(engine)),
            demo = false,
            atMillis = saved.atMillis,
            distanceDriven = saved.distanceDriven,
        )
    }

    private fun fromStored(store: InspectionStore, saved: StoredInspection, today: LocalDate): InspectionResult {
        val profile = saved.profile
        val engine = saved.snapshot
        return InspectionResult(
            readings = ScoreBridge.toReadings(engine),
            warnings = ScoreBridge.toWarnings(engine, isDemo = profile == Profile.DEMO),
            engine = engine,
            evaluation = Scoring.evaluate(engine, store.care(profile, today), Rules.hasRed(engine)),
            demo = profile == Profile.DEMO,
            atMillis = saved.atMillis,
            distanceDriven = saved.distanceDriven,
        )
    }

    companion object {
        private val gate = Mutex()

        fun currentKey(app: MocaApp): InspectKey = InspectKey(
            demo = app.prefs.demoEnabled,
            scenarioId = app.prefs.demoScenarioId,
            day = ScoreBridge.today(),
        )

        /**
         * 저장된 오늘 기록으로 점검 결과를 만든다(차를 다시 읽지 않는다).
         * 오늘 기록이 없거나(데모면 시나리오도 다르면) null.
         */
        fun stored(app: MocaApp): InspectionResult? {
            val demo = app.prefs.demoEnabled
            val profile = if (demo) Profile.DEMO else Profile.REAL
            val today = ScoreBridge.today()
            val saved = app.store.latest(profile) ?: return null
            if (saved.date != today) return null
            if (demo && saved.scenarioId != app.prefs.demoScenarioId) return null
            val engine = saved.snapshot
            return InspectionResult(
                readings = ScoreBridge.toReadings(engine),
                warnings = ScoreBridge.toWarnings(engine, isDemo = demo),
                engine = engine,
                evaluation = Scoring.evaluate(engine, app.store.care(profile, today), Rules.hasRed(engine)),
                demo = demo,
                atMillis = saved.atMillis,
                distanceDriven = saved.distanceDriven,
            )
        }
    }
}
