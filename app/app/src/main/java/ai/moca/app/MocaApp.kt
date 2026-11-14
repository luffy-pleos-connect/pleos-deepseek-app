package ai.moca.app

import android.app.Application
import ai.moca.app.data.InspectionStore
import ai.moca.app.data.Prefs
import ai.moca.app.inspect.InspectKey
import ai.moca.app.inspect.InspectionResult
import ai.moca.app.inspect.Inspector
import ai.moca.app.nav.NaviGateway
import ai.moca.app.nav.Place
import ai.moca.app.nav.Places
import ai.moca.app.nav.PleosNaviPort
import ai.moca.app.vehicle.DemoScenarios
import ai.moca.app.vehicle.PleosVehicleSource
import ai.moca.app.vehicle.VehicleSource

/** 계획 6.7 C-16. 도구 호출 직전에 말하는 필러. */
const val FILLER_TEXT = "확인해 볼게요"

class MocaApp : Application() {

    lateinit var prefs: Prefs
        private set

    /** 점검 기록 저장소. 화면·관리 점수는 이 기록을 쓴다. */
    lateinit var store: InspectionStore
        private set

    /** 실제 차량 소스. 데모 모드에서는 DemoScenarios를 쓴다. */
    lateinit var realVehicle: VehicleSource
        private set

    lateinit var demoVehicle: VehicleSource
        private set

    /** 차량 내비 연동(W8). 앱이 뜰 때 연결을 걸어 두고, 화면·보이스챗이 같은 인스턴스를 쓴다. */
    lateinit var navi: NaviGateway
        private set

    private var naviPort: PleosNaviPort? = null

    /** 이번 프로세스에서 가장 최근 점검. 화면은 이 값을 읽는다. */
    /**
     * 가장 최근 점검. 데모 여부·시나리오·날짜가 그때와 달라졌으면 낡은 결과이므로 돌려주지 않는다.
     * 없으면 저장된 오늘 기록으로 만들어 준다(차를 다시 읽지 않는다).
     */
    var latestInspection: InspectionResult? = null
        get() = field?.takeIf { lastInspectKey == Inspector.currentKey(this) }
            ?: if (::store.isInitialized) Inspector.stored(this) else null

    /** 마지막으로 기록을 남긴 점검의 키. 같으면 줄을 또 넣지 않는다. */
    var lastInspectKey: InspectKey? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        store = InspectionStore(filesDir)
        realVehicle = PleosVehicleSource(this)
        demoVehicle = DemoScenarios.source(prefs.demoScenarioId)
        naviPort = PleosNaviPort(this) { event ->
            when (event) {
                PleosNaviPort.Event.ROUTE_STARTED -> navi.onRouteStarted()
                PleosNaviPort.Event.ROUTE_CANCELLED -> navi.onRouteCancelled()
                PleosNaviPort.Event.WAYPOINT_CHANGED -> navi.onWaypointChanged()
                PleosNaviPort.Event.ERROR -> navi.onError()
                PleosNaviPort.Event.DESTINATION_ARRIVED -> navi.onDestinationArrived()
            }
        }
        navi = NaviGateway(naviPort!!, demoPlaces())
        naviPort?.connect()
    }

    override fun onTerminate() {
        naviPort?.release()
        naviPort = null
        super.onTerminate()
    }

    /** res/raw/places_demo.json 의 장소 목록. 데모 모드에서만 쓴다. */
    private fun demoPlaces(): List<Place> = try {
        Places.parse(resources.openRawResource(R.raw.places_demo).bufferedReader().use { it.readText() })
    } catch (e: Exception) {
        emptyList()
    }

    /** 현재 활성 프로필의 차량 소스. */
    fun vehicle(): VehicleSource {
        android.util.Log.i("MOCA_DEMO", "demoEnabled=" + prefs.demoEnabled + " scenario=" + prefs.demoScenarioId)
        return if (prefs.demoEnabled) DemoScenarios.source(prefs.demoScenarioId) else realVehicle
    }
}
