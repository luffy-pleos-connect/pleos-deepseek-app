package ai.moca.app.ui

import ai.moca.app.data.Prefs

/** 데모 켜기. 설정의 스위치와 홈의 「데모로 보기」가 같은 함수를 쓴다. */
object DemoMode {

    /** 홈의 「데모로 보기」는 문제가 보이는 D3 로 켠다. 설정 스위치는 지금 시나리오를 그대로 둔다. */
    const val HOME_SCENARIO = "D3"

    fun turnOn(prefs: Prefs, scenarioId: String? = null) {
        if (scenarioId != null) prefs.demoScenarioId = scenarioId
        prefs.demoEnabled = true
    }
}
