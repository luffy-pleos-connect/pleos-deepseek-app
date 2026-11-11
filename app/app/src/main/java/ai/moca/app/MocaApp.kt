package ai.moca.app

import android.app.Application
import ai.moca.app.data.Prefs
import ai.moca.app.inspect.InspectKey
import ai.moca.app.inspect.InspectionResult
import ai.moca.app.vehicle.DemoScenarios
import ai.moca.app.vehicle.PleosVehicleSource
import ai.moca.app.vehicle.VehicleSource

/** 계획 6.7 C-16. 도구 호출 직전에 말하는 필러. */
const val FILLER_TEXT = "확인해 볼게요"

class MocaApp : Application() {

    lateinit var prefs: Prefs
        private set

    /** 실제 차량 소스. 데모 모드에서는 DemoScenarios를 쓴다. */
    lateinit var realVehicle: VehicleSource
        private set

    lateinit var demoVehicle: VehicleSource
        private set

    /** 이번 프로세스에서 가장 최근 점검. 화면은 이 값을 읽는다. */
    /**
     * 가장 최근 점검. 데모 여부·시나리오·날짜가 그때와 달라졌으면 낡은 결과이므로 돌려주지 않는다.
     * 그러면 읽는 화면(홈·상세·순위)이 점검을 다시 돌린다.
     */
    var latestInspection: InspectionResult? = null
        get() = field?.takeIf { lastInspectKey == ai.moca.app.inspect.Inspector.currentKey(this) }

    /** 마지막으로 기록을 남긴 점검의 키. 같으면 줄을 또 넣지 않는다. */
    var lastInspectKey: InspectKey? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        ai.moca.app.score.ScoreBridge.realCare = { today ->
            moca.core.InspectionHistory.careScore(prefs.inspectionLog(false), today)
        }
        realVehicle = PleosVehicleSource(this)
        demoVehicle = DemoScenarios.source(prefs.demoScenarioId)
    }

    /** 현재 활성 프로필의 차량 소스. */
    fun vehicle(): VehicleSource {
        android.util.Log.i("MOCA_DEMO", "demoEnabled=" + prefs.demoEnabled + " scenario=" + prefs.demoScenarioId)
        return if (prefs.demoEnabled) DemoScenarios.source(prefs.demoScenarioId) else realVehicle
    }
}
