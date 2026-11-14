package ai.moca.app.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 개발 문서 W8: 차량 내비 연동. 최종정리 5장 6·8번, 6장 기능 4~6번, 13장.
 * lead 가 쓴 테스트다. 워커는 이 파일을 고치지 않는다.
 *
 * SDK 에는 «근처 장소 목록»을 돌려주는 함수가 없다(NaviHelper 3.0.5 AAR 확인). 그래서 길이 둘이다.
 *   실제 모드: 내비의 검색 화면을 연다(충전소는 내비의 «가까운 충전소» 화면). 장소는 내비가 찾고 운전자가 고른다.
 *   데모 모드: 앱에 넣은 장소 목록(res/raw/places_demo.json)의 좌표로 안내를 요청한다. 화면에는 «데모 데이터» 배지.
 *
 * 약속(ai.moca.app.nav):
 *   enum class PlaceKind(val label: String, val keyword: String) { REPAIR_SHOP("정비소","정비소"), TIRE_SHOP("타이어점","타이어"),
 *                                                                   GAS_STATION("주유소","주유소"), CHARGING_STATION("충전소","전기차 충전소") }
 *   data class Place(val kind: PlaceKind, val name: String, val latitude: Double, val longitude: Double, val address: String)
 *   object Places { fun parse(json: String): List<Place> }            깨진 글자·모르는 종류·좌표가 한국 밖인 줄은 버린다
 *   interface NaviPort {                                              실제 구현은 NaviHelper 를 감싼다
 *       fun requestRoute(place: Place): Boolean                       요청을 보냈으면 true (예외·미연결이면 false)
 *       fun addWaypoint(place: Place, index: Int): Boolean            index 는 1 또는 2
 *       fun cancelRoute(): Boolean
 *       fun openSearch(keyword: String): Boolean                      내비 검색 화면
 *       fun openNearbyCharging(): Boolean                             내비의 가까운 충전소 화면
 *   }
 *   data class NaviPlan(val question: String, val place: Place?, val demoData: Boolean)
 *   enum class NaviCode { ROUTE_REQUESTED, SEARCH_OPENED, STOP_ADDED, STOP_SEARCH_OPENED, STOP_LIMIT, CANCELLED, NOT_GUIDING, FAILED }
 *   data class NaviResult(val code: NaviCode, val message: String, val place: Place? = null, val demoData: Boolean = false) { val ok: Boolean }
 *   class NaviGateway(port: NaviPort, places: List<Place>) {
 *       val guiding: Boolean                 지금 길 안내 중인가
 *       val stops: Int                       이번 안내에 넣은 경유지 수
 *       fun plan(kind: PlaceKind, demo: Boolean): NaviPlan            누르기 전에 보여 줄 확인 문장
 *       fun planKeyword(keyword: String, demo: Boolean): NaviPlan     «강남역으로 안내해줘» 처럼 말한 장소
 *       fun navigateTo(kind: PlaceKind, demo: Boolean): NaviResult
 *       fun navigateToKeyword(keyword: String, demo: Boolean): NaviResult
 *       fun addStop(kind: PlaceKind, demo: Boolean): NaviResult
 *       fun cancel(): NaviResult
 *       내비가 알려 주는 사건(실제 구현의 리스너가 부른다):
 *       fun onRouteStarted(); fun onRouteCancelled(); fun onRouteEnded(); fun onDestinationArrived()
 *   }
 */
class NaviGatewayTest {

    private class FakePort(var alive: Boolean = true) : NaviPort {
        val routes = mutableListOf<Place>()
        val waypoints = mutableListOf<Pair<Place, Int>>()
        val searches = mutableListOf<String>()
        var cancels = 0
        var chargingScreens = 0
        override fun requestRoute(place: Place): Boolean { if (alive) routes += place; return alive }
        override fun addWaypoint(place: Place, index: Int): Boolean { if (alive) waypoints += place to index; return alive }
        override fun cancelRoute(): Boolean { if (alive) cancels++; return alive }
        override fun openSearch(keyword: String): Boolean { if (alive) searches += keyword; return alive }
        override fun openNearbyCharging(): Boolean { if (alive) chargingScreens++; return alive }
    }

    private val json = """
        [
          {"kind": "REPAIR_SHOP", "name": "MOCA 데모 정비소", "latitude": 37.566535, "longitude": 126.977969, "address": "서울특별시 중구 세종대로 110"},
          {"kind": "REPAIR_SHOP", "name": "둘째 정비소", "latitude": 37.57, "longitude": 126.98, "address": "서울특별시 종로구"},
          {"kind": "TIRE_SHOP", "name": "MOCA 데모 타이어점", "latitude": 37.561, "longitude": 126.986, "address": "서울특별시 중구"},
          {"kind": "GAS_STATION", "name": "MOCA 데모 주유소", "latitude": 37.575, "longitude": 126.977, "address": "서울특별시 종로구"},
          {"kind": "CHARGING_STATION", "name": "MOCA 데모 충전소", "latitude": 37.57, "longitude": 126.983, "address": "서울특별시 종로구"},
          {"kind": "CAR_WASH", "name": "모르는 종류", "latitude": 37.5, "longitude": 127.0, "address": "x"},
          {"kind": "GAS_STATION", "name": "위도와 경도가 바뀐 줄", "latitude": 126.977, "longitude": 37.575, "address": "x"},
          {"kind": "GAS_STATION", "name": "이름만 있는 줄"}
        ]
    """.trimIndent()
    private val places = Places.parse(json)
    private fun gateway(port: FakePort = FakePort()) = NaviGateway(port, places) to port

    // ── 장소 목록 ─────────────────────────────────────────────────────

    @Test
    fun `장소 목록에서 쓸 수 있는 줄만 읽는다`() {
        assertEquals(5, places.size)
        assertEquals(listOf("MOCA 데모 정비소", "둘째 정비소"), places.filter { it.kind == PlaceKind.REPAIR_SHOP }.map { it.name })
        assertTrue("위도 33~39, 경도 124~132 안의 줄만 남는다", places.all { it.latitude in 33.0..39.0 && it.longitude in 124.0..132.0 })
        assertTrue(Places.parse("{깨진").isEmpty())
        assertTrue(Places.parse("").isEmpty())
    }

    // ── 누르기 전 확인 문장 (최종정리 6장 UI 4번) ─────────────────────

    @Test
    fun `데모에서는 장소 이름으로 묻고 데모 데이터라고 알린다`() {
        val (g, _) = gateway()
        val plan = g.plan(PlaceKind.REPAIR_SHOP, demo = true)
        assertEquals("MOCA 데모 정비소로 안내할까요?", plan.question)
        assertEquals("MOCA 데모 정비소", plan.place!!.name)
        assertTrue(plan.demoData)
    }

    @Test
    fun `실제에서는 내비에서 찾겠다고 묻는다`() {
        val (g, _) = gateway()
        val plan = g.plan(PlaceKind.GAS_STATION, demo = false)
        assertEquals("내비에서 가까운 주유소를 찾을까요?", plan.question)
        assertNull(plan.place)
        assertFalse(plan.demoData)
        assertEquals("내비에서 강남역을 찾을까요?", g.planKeyword("강남역", demo = false).question)
    }

    // ── 목적지 안내 ───────────────────────────────────────────────────

    @Test
    fun `데모에서 정비소 안내는 목록 첫 장소의 좌표로 요청한다`() {
        val (g, port) = gateway()
        val result = g.navigateTo(PlaceKind.REPAIR_SHOP, demo = true)
        assertEquals(NaviCode.ROUTE_REQUESTED, result.code)
        assertTrue(result.ok)
        assertTrue(result.demoData)
        assertEquals("MOCA 데모 정비소", result.place!!.name)
        assertEquals("MOCA 데모 정비소로 길 안내를 요청했어요", result.message)
        assertEquals(1, port.routes.size)
        assertEquals(37.566535, port.routes[0].latitude, 1e-9)
        assertEquals(126.977969, port.routes[0].longitude, 1e-9)
        assertFalse("내비가 시작을 알려 주기 전에는 안내 중이 아니다", g.guiding)
        g.onRouteStarted()
        assertTrue(g.guiding)
    }

    @Test
    fun `실제에서 정비소 타이어점 주유소는 내비 검색 화면을 연다`() {
        val (g, port) = gateway()
        for ((kind, keyword) in listOf(PlaceKind.REPAIR_SHOP to "정비소", PlaceKind.TIRE_SHOP to "타이어", PlaceKind.GAS_STATION to "주유소")) {
            val result = g.navigateTo(kind, demo = false)
            assertEquals(NaviCode.SEARCH_OPENED, result.code)
            assertFalse(result.demoData)
            assertNull(result.place)
            assertEquals(keyword, port.searches.last())
        }
        assertEquals("내비에서 가까운 정비소를 찾았어요. 목록에서 골라 주세요", g.navigateTo(PlaceKind.REPAIR_SHOP, demo = false).message)
        assertTrue(port.routes.isEmpty())
    }

    @Test
    fun `실제에서 충전소는 내비의 가까운 충전소 화면을 연다`() {
        val (g, port) = gateway()
        val result = g.navigateTo(PlaceKind.CHARGING_STATION, demo = false)
        assertEquals(NaviCode.SEARCH_OPENED, result.code)
        assertEquals(1, port.chargingScreens)
        assertTrue(port.searches.isEmpty())
    }

    @Test
    fun `말한 장소는 데모 목록에 이름이 있으면 그 좌표로 없으면 내비 검색으로 간다`() {
        val (g, port) = gateway()
        assertEquals(NaviCode.ROUTE_REQUESTED, g.navigateToKeyword("둘째 정비소", demo = true).code)
        assertEquals("둘째 정비소", port.routes.single().name)
        assertEquals(NaviCode.SEARCH_OPENED, g.navigateToKeyword("강남역", demo = true).code)
        assertEquals(NaviCode.SEARCH_OPENED, g.navigateToKeyword("강남역", demo = false).code)
        assertEquals(listOf("강남역", "강남역"), port.searches)
        assertEquals(NaviCode.FAILED, g.navigateToKeyword("   ", demo = false).code)
    }

    @Test
    fun `내비에 연결되지 않으면 실패를 알린다`() {
        val (g, port) = gateway(FakePort(alive = false))
        for (result in listOf(g.navigateTo(PlaceKind.REPAIR_SHOP, demo = true), g.navigateTo(PlaceKind.REPAIR_SHOP, demo = false), g.cancel())) {
            assertFalse(result.ok)
        }
        assertEquals(NaviCode.FAILED, g.navigateTo(PlaceKind.GAS_STATION, demo = true).code)
        assertEquals("내비게이션에 연결하지 못했어요. 내비에서 직접 찾아 주세요", g.navigateTo(PlaceKind.GAS_STATION, demo = true).message)
        assertTrue(port.routes.isEmpty())
    }

    // ── 경유지 (최종정리 6장 기능 6번) ────────────────────────────────

    @Test
    fun `안내 중이 아니면 경유지를 넣지 않는다`() {
        val (g, port) = gateway()
        val result = g.addStop(PlaceKind.GAS_STATION, demo = true)
        assertEquals(NaviCode.NOT_GUIDING, result.code)
        assertFalse(result.ok)
        assertEquals("지금은 길 안내 중이 아니에요. 먼저 목적지를 알려 주세요", result.message)
        assertTrue(port.waypoints.isEmpty())
    }

    @Test
    fun `데모에서 경유지는 두 곳까지 넣고 세 번째는 넣지 않는다`() {
        val (g, port) = gateway()
        g.navigateTo(PlaceKind.REPAIR_SHOP, demo = true)
        g.onRouteStarted()
        val first = g.addStop(PlaceKind.GAS_STATION, demo = true)
        assertEquals(NaviCode.STOP_ADDED, first.code)
        assertEquals("MOCA 데모 주유소를 경유지로 넣었어요", first.message)
        val second = g.addStop(PlaceKind.CHARGING_STATION, demo = true)
        assertEquals(NaviCode.STOP_ADDED, second.code)
        assertEquals(listOf(1, 2), port.waypoints.map { it.second })
        assertEquals(2, g.stops)
        val third = g.addStop(PlaceKind.TIRE_SHOP, demo = true)
        assertEquals(NaviCode.STOP_LIMIT, third.code)
        assertFalse(third.ok)
        assertEquals("경유지는 2곳까지 넣을 수 있어요", third.message)
        assertEquals(2, port.waypoints.size)
        assertEquals("목적지는 바뀌지 않는다", 1, port.routes.size)
    }

    @Test
    fun `실제에서 경유지는 내비 검색 화면으로 넘긴다`() {
        val (g, port) = gateway()
        g.onRouteStarted()
        val result = g.addStop(PlaceKind.GAS_STATION, demo = false)
        assertEquals(NaviCode.STOP_SEARCH_OPENED, result.code)
        assertTrue(result.ok)
        assertEquals("내비에서 가까운 주유소를 찾았어요. 경유지로 추가해 주세요", result.message)
        assertEquals(listOf("주유소"), port.searches)
        assertTrue(port.waypoints.isEmpty())
    }

    // ── 취소와 안내 상태 ──────────────────────────────────────────────

    @Test
    fun `안내를 취소하면 경유지 수도 처음으로 돌아간다`() {
        val (g, port) = gateway()
        g.navigateTo(PlaceKind.REPAIR_SHOP, demo = true)
        g.onRouteStarted()
        g.addStop(PlaceKind.GAS_STATION, demo = true)
        val result = g.cancel()
        assertEquals(NaviCode.CANCELLED, result.code)
        assertEquals("길 안내를 껐어요", result.message)
        assertEquals(1, port.cancels)
        g.onRouteCancelled()
        assertFalse(g.guiding)
        assertEquals(0, g.stops)
    }

    @Test
    fun `안내 중이 아닐 때 취소하면 그렇다고 알린다`() {
        val (g, port) = gateway()
        val result = g.cancel()
        assertEquals(NaviCode.NOT_GUIDING, result.code)
        assertEquals("지금은 길 안내 중이 아니에요", result.message)
        assertEquals(0, port.cancels)
    }

    @Test
    fun `도착하거나 안내가 끝나면 안내 중이 아니다`() {
        val (g, _) = gateway()
        g.onRouteStarted(); assertTrue(g.guiding)
        g.onDestinationArrived(); assertFalse(g.guiding)
        g.onRouteStarted(); g.onRouteEnded(); assertFalse(g.guiding)
        g.onRouteStarted(); g.addStop(PlaceKind.GAS_STATION, demo = true); assertEquals(1, g.stops)
        g.onRouteStarted()
        assertEquals("경유지를 넣으면 내비가 경로를 다시 잡고 시작을 또 알릴 수 있다. 그때 수를 지우면 안 된다", 1, g.stops)
        g.navigateTo(PlaceKind.REPAIR_SHOP, demo = true)
        assertEquals("새 목적지를 요청하면 경유지 수는 0 이다", 0, g.stops)
    }

    @Test
    fun `데모 목록에 그 종류의 장소가 없으면 내비 검색으로 넘긴다`() {
        val port = FakePort()
        val g = NaviGateway(port, places.filter { it.kind != PlaceKind.TIRE_SHOP })
        val result = g.navigateTo(PlaceKind.TIRE_SHOP, demo = true)
        assertEquals(NaviCode.SEARCH_OPENED, result.code)
        assertFalse(result.demoData)
        assertEquals(listOf("타이어"), port.searches)
        assertNotNull(g.plan(PlaceKind.TIRE_SHOP, demo = true).question)
    }
}
