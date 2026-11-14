package ai.moca.app.nav

/**
 * 내비 SDK 를 감싼 경계. [NaviGateway] 는 이 인터페이스만 알므로 JVM 테스트에서 가짜 구현을 쓴다.
 * 모든 함수는 «바로» 답한다. 연결이 아직 안 됐거나 요청이 예외로 끝나면 false.
 */
interface NaviPort {
    /** 목적지 안내를 요청한다. */
    fun requestRoute(place: Place): Boolean

    /** 경유지를 넣는다. [index] 는 1 또는 2. */
    fun addWaypoint(place: Place, index: Int): Boolean

    fun cancelRoute(): Boolean

    /** 내비 검색 화면을 연다. */
    fun openSearch(keyword: String): Boolean

    /** 내비의 가까운 충전소 화면을 연다. */
    fun openNearbyCharging(): Boolean
}
