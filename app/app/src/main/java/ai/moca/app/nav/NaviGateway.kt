package ai.moca.app.nav

import java.util.concurrent.atomic.AtomicInteger

/** 누르기 전에 보여 줄 확인 문장(최종정리 6장 UI 4번). [place] 는 데모 모드에서 안내할 곳. */
data class NaviPlan(val question: String, val place: Place?, val demoData: Boolean)

enum class NaviCode {
    ROUTE_REQUESTED,
    SEARCH_OPENED,
    STOP_ADDED,
    STOP_SEARCH_OPENED,
    STOP_LIMIT,
    CANCELLED,
    NOT_GUIDING,
    FAILED,
}

/** 내비 조작 결과. [message] 는 화면·보이스챗이 그대로 읽는다. */
data class NaviResult(
    val code: NaviCode,
    val message: String,
    val place: Place? = null,
    val demoData: Boolean = false,
) {
    val ok: Boolean
        get() = code == NaviCode.ROUTE_REQUESTED || code == NaviCode.SEARCH_OPENED ||
            code == NaviCode.STOP_ADDED || code == NaviCode.STOP_SEARCH_OPENED ||
            code == NaviCode.CANCELLED
}

/**
 * 내비 조작의 규칙(W8). 안드로이드 클래스를 쓰지 않으므로 JVM 테스트가 그대로 돈다.
 *
 * SDK 에 «근처 장소 목록» 함수가 없어 길이 둘이다(개발문서 2.2절, 최종정리 13장).
 *   실제 모드: 내비의 검색 화면을 연다. 장소는 내비가 찾고 운전자가 고른다.
 *   데모 모드: 앱에 넣은 장소 목록([places])의 첫 좌표로 안내를 요청한다. 화면에는 «데모 데이터» 배지.
 * 데모 목록에 그 종류가 없으면 실제 모드와 같은 검색 화면으로 넘긴다.
 *
 * 안내 중 여부는 내비가 알려 주는 사건([onRouteStarted] 등)으로만 바뀐다.
 * 요청이 성공해도 경로 계산은 끝나지 않았기 때문이다.
 */
class NaviGateway(private val port: NaviPort, private val places: List<Place>) {

    /** 지금 길 안내 중인가. */
    @Volatile
    var guiding: Boolean = false
        private set

    /** 이번 안내에 넣은 경유지 수. 새 목적지·안내 종료에서 0 이 된다. */
    private val stopCount = AtomicInteger(0)
    val stops: Int
        get() = stopCount.get()

    fun plan(kind: PlaceKind, demo: Boolean): NaviPlan {
        val place = if (demo) firstOf(kind) else null
        return if (place != null) {
            NaviPlan("${place.name}${direction(place.name)} 안내할까요?", place, demoData = true)
        } else {
            NaviPlan("내비에서 가까운 ${kind.label}${objectParticle(kind.label)} 찾을까요?", null, demoData = false)
        }
    }

    fun planKeyword(keyword: String, demo: Boolean): NaviPlan {
        val place = if (demo) namedPlace(keyword) else null
        return if (place != null) {
            NaviPlan("${place.name}${direction(place.name)} 안내할까요?", place, demoData = true)
        } else {
            val trimmed = keyword.trim()
            NaviPlan("내비에서 $trimmed${objectParticle(trimmed)} 찾을까요?", null, demoData = false)
        }
    }

    fun navigateTo(kind: PlaceKind, demo: Boolean): NaviResult {
        val place = if (demo) firstOf(kind) else null
        return if (place != null) requestRoute(place, demoData = true) else openSearch(kind, forStop = false)
    }

    fun navigateToKeyword(keyword: String, demo: Boolean): NaviResult {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return failed()
        val place = if (demo) namedPlace(trimmed) else null
        return if (place != null) requestRoute(place, demoData = true) else openSearchKeyword(trimmed)
    }

    fun addStop(kind: PlaceKind, demo: Boolean): NaviResult {
        if (!guiding) {
            return NaviResult(NaviCode.NOT_GUIDING, MSG_NOT_GUIDING_FIRST)
        }
        if (stops >= MAX_STOPS) {
            return NaviResult(NaviCode.STOP_LIMIT, MSG_STOP_LIMIT)
        }
        val place = if (demo) firstOf(kind) else null
        if (place == null) return openSearch(kind, forStop = true)
        val index = stops + 1
        val sent = runCatching { port.addWaypoint(place, index) }.getOrDefault(false)
        if (!sent) return failed()
        stopCount.incrementAndGet()
        return NaviResult(NaviCode.STOP_ADDED, "${place.name}${objectParticle(place.name)} 경유지로 넣었어요", place, demoData = true)
    }

    fun cancel(): NaviResult {
        if (!guiding) return NaviResult(NaviCode.NOT_GUIDING, MSG_NOT_GUIDING)
        val sent = runCatching { port.cancelRoute() }.getOrDefault(false)
        if (!sent) return failed()
        stopCount.set(0)
        return NaviResult(NaviCode.CANCELLED, MSG_CANCELLED)
    }

    // ── 내비가 알려 주는 사건 ────────────────────────────────────────

    fun onRouteStarted() {
        // 경유지를 넣으면 내비가 경로를 다시 잡고 시작을 또 알린다. 그때 경유지 수를 지우면 안 된다.
        guiding = true
    }

    fun onRouteCancelled() = finished()

    fun onRouteEnded() = finished()

    fun onDestinationArrived() = finished()

    private fun finished() {
        guiding = false
        stopCount.set(0)
    }

    // ── 안쪽 ─────────────────────────────────────────────────────────

    private fun requestRoute(place: Place, demoData: Boolean): NaviResult {
        stopCount.set(0)
        val sent = runCatching { port.requestRoute(place) }.getOrDefault(false)
        if (!sent) return failed()
        return NaviResult(
            NaviCode.ROUTE_REQUESTED,
            "${place.name}${direction(place.name)} 길 안내를 요청했어요",
            place,
            demoData = demoData,
        )
    }

    private fun openSearch(kind: PlaceKind, forStop: Boolean): NaviResult {
        val sent = runCatching {
            if (kind == PlaceKind.CHARGING_STATION) port.openNearbyCharging() else port.openSearch(kind.keyword)
        }.getOrDefault(false)
        if (!sent) return failed()
        val code = if (forStop) NaviCode.STOP_SEARCH_OPENED else NaviCode.SEARCH_OPENED
        val tail = if (forStop) MSG_SEARCH_TAIL_STOP else MSG_SEARCH_TAIL
        return NaviResult(code, "내비에서 가까운 ${kind.label}${objectParticle(kind.label)} 찾았어요. $tail")
    }

    private fun openSearchKeyword(keyword: String): NaviResult {
        val sent = runCatching { port.openSearch(keyword) }.getOrDefault(false)
        if (!sent) return failed()
        return NaviResult(NaviCode.SEARCH_OPENED, "내비에서 가까운 $keyword${objectParticle(keyword)} 찾았어요. $MSG_SEARCH_TAIL")
    }

    private fun firstOf(kind: PlaceKind): Place? = places.firstOrNull { it.kind == kind }

    private fun namedPlace(keyword: String): Place? = places.firstOrNull { it.name == keyword }

    private fun failed() = NaviResult(NaviCode.FAILED, MSG_FAILED)

    private companion object {
        const val MAX_STOPS = 2

        const val MSG_FAILED = "내비게이션에 연결하지 못했어요. 내비에서 직접 찾아 주세요"
        const val MSG_CANCELLED = "길 안내를 껐어요"
        const val MSG_NOT_GUIDING = "지금은 길 안내 중이 아니에요"
        const val MSG_NOT_GUIDING_FIRST = "지금은 길 안내 중이 아니에요. 먼저 목적지를 알려 주세요"
        const val MSG_STOP_LIMIT = "경유지는 2곳까지 넣을 수 있어요"
        const val MSG_SEARCH_TAIL = "목록에서 골라 주세요"
        const val MSG_SEARCH_TAIL_STOP = "경유지로 추가해 주세요"

        /** 목적격 조사. 받침이 있으면 «을», 없으면 «를». */
        fun objectParticle(word: String): String = if (hasFinalConsonant(word)) "을" else "를"

        /** 방향 조사. 받침이 있으면 «으로», 없거나 ㄹ 이면 «로». */
        fun direction(word: String): String {
            val jong = finalConsonant(word)
            return if (jong != 0 && jong != 8) "으로" else "로"
        }

        private fun hasFinalConsonant(word: String): Boolean = finalConsonant(word) != 0

        /** 마지막 글자의 종성 번호(0 = 없음). 한글 음절이 아니면 0. */
        private fun finalConsonant(word: String): Int {
            val last = word.trim().lastOrNull() ?: return 0
            if (last !in '\uAC00'..'\uD7A3') return 0
            return (last - '\uAC00') % 28
        }
    }
}
