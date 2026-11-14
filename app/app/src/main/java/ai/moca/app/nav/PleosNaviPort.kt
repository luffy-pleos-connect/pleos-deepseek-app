package ai.moca.app.nav

import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NaviHelper 3.0.5 를 감싼 실제 내비 연결(개발문서 2.2절).
 *
 * S3 에서 리스너 콜백은 없었고, onConnected 다음 requestNavigationScreen 이 예외 없이 돌아온 것이
 * 응답이었다. 그래서 연결은 프로세스 동안 하나만 유지하고, 앱이 뜰 때([connect]) 미리 걸어 둔다.
 * [NaviPort] 의 함수는 «바로 답»해야 하므로 연결이 아직이면 false 를 돌려준다.
 * 예외는 밖으로 내지 않는다.
 */
class PleosNaviPort(
    context: Context,
    private val onEvent: (Event) -> Unit,
) : NaviPort {

    /** 내비가 알려 주는 사건. [NaviGateway] 의 같은 이름 함수로 넘긴다. */
    enum class Event { ROUTE_STARTED, ROUTE_CANCELLED, ROUTE_ENDED, DESTINATION_ARRIVED }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var navi: NaviHelper? = null
    private var connecting = false

    @Volatile
    private var connected = false

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: ai.pleos.playground.navi.data.RouteStartInfo) {
            Log.i(TAG, "onRouteStarted ${info.name}")
            onEvent(Event.ROUTE_STARTED)
        }

        override fun onRouteCancelled() {
            Log.i(TAG, "onRouteCancelled")
            onEvent(Event.ROUTE_CANCELLED)
        }

        override fun onRouteEnded() {
            onEvent(Event.ROUTE_ENDED)
        }

        override fun onError(errorCode: ai.pleos.playground.navi.constants.NaviErrorCode) {
            Log.w(TAG, "onError $errorCode")
        }

        override fun onWaypointChanged(info: ai.pleos.playground.navi.data.WaypointChangedInfo) {
            Log.i(TAG, "onWaypointChanged $info")
        }

        override fun onDestinationArrived(info: ai.pleos.playground.navi.data.DestinationArrivedInfo) {
            Log.i(TAG, "onDestinationArrived")
            onEvent(Event.DESTINATION_ARRIVED)
        }
    }

    /** 앱이 뜰 때 한 번 부른다. 실패해도 앱은 계속 돈다(내비 기능만 false). */
    fun connect() = handler.post { connectOnMain() }

    /** 앱을 닫을 때 리스너를 떼고 놓아 준다. */
    fun release() {
        handler.post {
            val helper = navi ?: return@post
            runCatching { helper.removeListener(listener) }
            runCatching { helper.release() }
            navi = null
            connected = false
            connecting = false
        }
    }

    override fun requestRoute(place: Place): Boolean = send("requestRoute ${place.name}") { helper ->
        helper.requestRoute(
            RouteInfo(
                latitude = place.latitude,
                longitude = place.longitude,
                poiName = place.name,
                poiId = "",
                address = place.address,
                poiSubId = "",
            ),
        )
    }

    override fun addWaypoint(place: Place, index: Int): Boolean {
        val waypointIndex = when (index) {
            1 -> WaypointIndex.FIRST
            2 -> WaypointIndex.SECOND
            else -> return false
        }
        return send("addWaypoint $index ${place.name}") { helper ->
            helper.addWaypoint(
                RequestWaypointInfo(
                    latitude = place.latitude,
                    longitude = place.longitude,
                    poiName = place.name,
                    waypointIndex = waypointIndex,
                    poiId = "",
                    address = place.address,
                    poiSubId = "",
                ),
            )
        }
    }

    override fun cancelRoute(): Boolean = send("cancelRoute") { helper -> helper.cancelRoute() }

    override fun openSearch(keyword: String): Boolean {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return false
        return send("searchPlace $trimmed") { helper -> helper.requestNavigationScreen(NavigationScreenRequest.SearchPlace(trimmed)) }
    }

    override fun openNearbyCharging(): Boolean =
        send("nearbyCharging") { helper -> helper.requestNavigationScreen(NavigationScreenRequest.NearbyChargingStations) }

    /**
     * 연결된 헬퍼에 요청을 보낸다. 연결 전·연결 실패·예외는 모두 false.
     * 연결이 없으면 다음 요청을 위해 다시 연결을 걸어 둔다(앱이 뜰 때 내비가 아직 준비되지 않았을 수 있다).
     * [call] 은 헬퍼를 인자로 받는다. 받는 쪽 이름으로 부르면 이 클래스의 같은 이름 함수와 헷갈린다.
     */
    private fun send(what: String, call: (NaviHelper) -> Unit): Boolean {
        val helper = navi
        if (helper == null || !connected) {
            Log.w(TAG, "$what: not connected, reconnecting")
            connect()
            return false
        }
        val result = runCatching { call(helper) }
        result.exceptionOrNull()?.let { Log.w(TAG, "$what failed: ${it.javaClass.simpleName}: ${it.message}") }
        if (result.isSuccess) Log.i(TAG, "$what sent")
        return result.isSuccess
    }

    private fun connectOnMain() {
        if (connected || connecting) return
        connecting = true
        val finished = AtomicBoolean(false)
        val created = runCatching { NaviHelper(appContext) }.getOrNull()
        if (created == null) {
            connecting = false
            return
        }
        navi = created
        val timeout = Runnable { settle(created, finished, false) }
        handler.postDelayed(timeout, CONNECT_TIMEOUT_MS)
        try {
            created.initialize(
                onConnected = {
                    if (finished.get()) return@initialize
                    handler.removeCallbacks(timeout)
                    runCatching { created.addListener(listener) }
                    settle(created, finished, true)
                },
                onFailed = {
                    handler.removeCallbacks(timeout)
                    settle(created, finished, false)
                },
            )
        } catch (e: Exception) {
            handler.removeCallbacks(timeout)
            settle(created, finished, false)
        }
    }

    private fun settle(helper: NaviHelper, finished: AtomicBoolean, connectedOk: Boolean) {
        if (!finished.compareAndSet(false, true)) return
        connecting = false
        connected = connectedOk
        Log.i(TAG, "connected=$connectedOk")
        if (!connectedOk) {
            runCatching { helper.release() }
            if (navi === helper) navi = null
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val TAG = "MOCA_NAVI"
    }
}
