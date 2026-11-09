package ai.moca.app

import android.app.Application
import ai.moca.app.data.Prefs
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

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        realVehicle = PleosVehicleSource(this)
        demoVehicle = DemoScenarios.source(prefs.demoScenarioId)
    }

    /** 현재 활성 프로필의 차량 소스. */
    fun vehicle(): VehicleSource =
        if (prefs.demoEnabled) DemoScenarios.source(prefs.demoScenarioId) else realVehicle
}
