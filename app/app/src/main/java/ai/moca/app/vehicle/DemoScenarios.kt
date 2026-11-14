package ai.moca.app.vehicle

import ai.moca.app.score.ScoreBridge
import moca.core.Demo

/**
 * 데모 시나리오 D0~D7. 값은 손으로 쓰지 않고 엔진의 데모 데이터(`moca.core.Demo`)에서 가져온다.
 */
enum class DemoScenario(val id: String) {
    D0("D0"),
    D1("D1"),
    D2("D2"),
    D3("D3"),
    D4("D4"),
    D5("D5"),
    D6("D6"),
    D7("D7"),
    ;

    /** 엔진 데모 시나리오(제목·상대·기대값의 원본). */
    private val engine: moca.core.DemoScenario
        get() = Demo.scenarios().first { it.first.id == id }.first

    val label: String get() = engine.title

    /** 점검 결과 기대값(최종정리 2.5절). 화면·테스트가 이 값을 쓴다. */
    val expectedGrade: String get() = engine.expectedGrade.label
    val expectedTotal: Int get() = engine.expectedDisplayed

    fun readings(): LinkedHashMap<String, ItemReading> = LinkedHashMap(ScoreBridge.toReadings(snapshot()))

    fun warnings(): WarningSummary = ScoreBridge.toWarnings(snapshot(), isDemo = true)

    /** 계획 10장의 고정 상대. */
    fun opponentNickname(): String = engine.opponent

    private fun snapshot() = Demo.snapshotOf(id)

    companion object {
        fun byId(id: String?): DemoScenario = entries.firstOrNull { it.id == id } ?: D3
    }
}

object DemoScenarios {

    fun source(scenarioId: String?): VehicleSource = object : VehicleSource {
        private val scenario = DemoScenario.byId(scenarioId)
        private val snapshot = Demo.snapshotOf(scenario.id)
        private var watcher: ((WarningSummary) -> Unit)? = null

        override suspend fun read(): VehicleSnapshot =
            VehicleSnapshot(ScoreBridge.toReadings(snapshot), System.currentTimeMillis(), isDemo = true)

        override fun cachedWarnings(): WarningSummary = ScoreBridge.toWarnings(snapshot, isDemo = true)

        override fun startWatchingWarnings(onChange: (WarningSummary) -> Unit) {
            watcher = onChange
            onChange(cachedWarnings())
        }

        override fun stopWatchingWarnings() {
            watcher = null
        }
    }
}
