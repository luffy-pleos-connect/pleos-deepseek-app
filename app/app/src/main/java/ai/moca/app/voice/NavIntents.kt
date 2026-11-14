package ai.moca.app.voice

import ai.moca.app.nav.PlaceKind
import ai.moca.app.ui.model.SolutionButton
import ai.moca.app.ui.model.SolutionKind
import ai.moca.app.ui.model.Solutions
import moca.core.VehicleSnapshot

/** [toolName] 은 이 요청을 처리하는 도구 이름. 근거 줄·기기 안 의도가 같은 이름을 쓴다. */
enum class NavAction(val toolName: String) {
    GO("start_navigation_to_service"),
    ADD_STOP("add_stop"),
    CANCEL("cancel_navigation"),
}

/** 길 안내 요청 하나. 말한 장소 이름이 있으면 [keyword], 없으면 가까운 [kind]. */
data class NavRequest(val action: NavAction, val kind: PlaceKind? = null, val keyword: String? = null)

/**
 * 길 안내 말을 기기에서 먼저 알아듣는다(최종정리 6장 기능 4~6·8·9번).
 * 안드로이드 클래스를 쓰지 않으므로 JVM 테스트가 그대로 돈다.
 */
object NavIntents {

    private val KINDS = listOf(
        PlaceKind.CHARGING_STATION to listOf("충전소"),
        PlaceKind.TIRE_SHOP to listOf("타이어점", "타이어샵", "타이어 가게"),
        PlaceKind.REPAIR_SHOP to listOf("정비소", "정비센터", "정비 센터", "카센터", "서비스센터", "서비스 센터", "수리점"),
        PlaceKind.GAS_STATION to listOf("주유소", "기름"),
    )

    private val STOP_WORDS = listOf("들르", "들러", "들를", "들렀", "들려", "경유")
    private val CANCEL_TARGETS = listOf("안내", "길", "경로", "내비", "네비")
    private val CANCEL_WORDS = Regex("취소|그만|종료|중단|끝내|꺼\\s?줘|꺼\\s?주|끄자")

    /** 종류를 말했을 때의 «가자» 말. «정비소 가야 해?» 같은 물음은 여기에 없다. */
    private val GO_WORDS = Regex("안내|찾아|가자|가\\s?줘|갈래|가고\\s?싶|데려")

    /** 장소 이름을 말했을 때: «○○(으로|까지) 안내해줘», «○○ 가자». «찾아줘» 는 뺀다(경고등 원인 찾기와 섞인다). */
    private val PLACE_PATTERN = Regex(
        "(.+?)(?:으로|로|까지|에|을|를)?\\s*(?:길\\s*안내|안내|가자|가\\s?줘|갈래|가고\\s?싶|데려가|데려다)",
    )
    private val FILLER_PREFIX = Regex("^(가까운|근처|지금|여기서|여기에서|저기|거기)\\s*")

    /** 앱 화면 이름·차 상태 낱말은 장소가 아니다(«홈으로 가 줘» 는 화면 열기다). */
    private val NOT_A_PLACE = listOf("홈", "메인", "상태관리", "상세", "순위", "랭킹", "설정", "대전", "보이스")
    private val CAR_WORDS = listOf("경고", "점검", "등급", "점수", "부품", "오일", "타이어", "브레이크", "배터리", "워셔", "화면")
    private const val MAX_PLACE_LENGTH = 20

    fun detect(question: String): NavRequest? {
        val q = question.trim()
        if (q.isEmpty()) return null
        if (CANCEL_TARGETS.any { q.contains(it) } && CANCEL_WORDS.containsMatchIn(q)) {
            return NavRequest(NavAction.CANCEL)
        }
        val kind = KINDS.firstOrNull { (_, words) -> words.any { q.contains(it) } }?.first
        if (kind != null) {
            return when {
                STOP_WORDS.any { q.contains(it) } -> NavRequest(NavAction.ADD_STOP, kind = kind)
                GO_WORDS.containsMatchIn(q) -> NavRequest(NavAction.GO, kind = kind)
                else -> null
            }
        }
        val place = placeOf(q) ?: return null
        return NavRequest(NavAction.GO, keyword = place)
    }

    private fun placeOf(q: String): String? {
        val raw = PLACE_PATTERN.find(q)?.groupValues?.get(1) ?: return null
        val place = raw.trim().replace(FILLER_PREFIX, "").trim()
        if (place.isEmpty() || place.length > MAX_PLACE_LENGTH) return null
        if (place.any { it in "?!.,~" }) return null
        if (NOT_A_PLACE.any { place == it } || CAR_WORDS.any { place.contains(it) }) return null
        return place
    }

    // ── 확인 카드가 떠 있을 때의 승낙·거절 ─────────────────────────────

    private val SPLIT = Regex("[\\s,.!?~]+")
    private val PUNCT = Regex("[\\s,.!?~]")
    private const val MAX_REPLY = 8

    private val YES_FIRST = setOf("응", "네", "넵", "예", "어", "그래", "그래요", "좋아", "좋아요", "오케이", "ok", "맞아")
    private val YES_PHRASES = listOf("안내해", "안내시작", "안내를시작", "시작해", "출발", "가자", "가줘")
    private val NO_FIRST = setOf(
        "아니", "아니요", "아니오", "아냐", "싫어", "싫어요", "됐어", "됐어요", "괜찮아", "괜찮아요", "취소", "취소해", "취소해줘", "그만", "그만해",
    )
    private val NO_PHRASES = listOf("하지마", "말아", "안할래")

    /** 짧은 대답만 승낙·거절로 본다. 「어떻게」·「어때」 같은 질문 앞머리를 「어」로 오해하지 않도록 첫 낱말은 통째로 맞춘다. */
    fun isYes(answer: String): Boolean {
        val squashed = answer.replace(PUNCT, "")
        if (squashed.isEmpty() || squashed.length > MAX_REPLY || isNo(answer)) return false
        val first = firstWord(answer)
        return first in YES_FIRST || YES_PHRASES.any { squashed.startsWith(it) }
    }

    fun isNo(answer: String): Boolean {
        val squashed = answer.replace(PUNCT, "")
        if (squashed.isEmpty() || squashed.length > MAX_REPLY) return false
        return firstWord(answer) in NO_FIRST || NO_PHRASES.any { squashed.contains(it) }
    }

    private fun firstWord(answer: String): String =
        answer.trim().split(SPLIT).firstOrNull().orEmpty().lowercase()
}

/**
 * 화면에 들어올 때 한 번 먼저 제안할 길 안내(최종정리 6장 기능 5번).
 * 정비 항목(빨간 경고등 포함) → 연료 → 배터리 순으로 하나만. 용품 구매·직접 해결은 제안하지 않는다.
 */
object NavSuggestion {

    fun of(snapshot: VehicleSnapshot): NavRequest? {
        val solutions = Solutions.of(snapshot)
        val kind = when {
            solutions.any { SolutionKind.REPAIR_SHOP in it.kinds } -> PlaceKind.REPAIR_SHOP
            solutions.any { SolutionButton.GAS_STATION in it.buttons } -> PlaceKind.GAS_STATION
            solutions.any { SolutionButton.CHARGING_STATION in it.buttons } -> PlaceKind.CHARGING_STATION
            else -> null
        }
        return kind?.let { NavRequest(NavAction.GO, kind = it) }
    }
}
