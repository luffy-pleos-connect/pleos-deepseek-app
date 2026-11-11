package ai.moca.app.link

import moca.core.FixAction

enum class LinkKind { NAVI, QR, NONE }

data class LinkDecision(
    val kind: LinkKind,
    val keyword: String?,
    val url: String?,
    /** QR 은 주행 중에 열지 않는다. 내비게이션 검색은 주행 중에도 연다. */
    val blockedWhileDriving: Boolean,
)

/**
 * 어떤 [FixAction] 이 내비게이션 검색으로 가는지, QR 로 가는지 정한다.
 * [searchPlaceWorks] 는 S3 시험에서 `SearchPlace` 가 됐는가이다.
 */
object LinkRoute {
    /** S3(2026-11-11): SearchPlace("정비소") 가 내비 검색 화면을 열었다. */
    const val SEARCH_PLACE_WORKS = true

    const val MSG_BLOCKED_DRIVING = "정차한 뒤에 볼 수 있어요"
    const val MSG_FIND_IN_NAVI = "내비게이션에서 직접 찾아 주세요"
    const val MSG_NAVI_OPENED = "내비게이션 검색을 열었어요"
    const val MSG_QR_SHOWN = "휴대폰으로 열 수 있는 QR 을 띄웠어요"
    const val MSG_NAVI_FAILED_QR = "내비게이션에 연결하지 못해 QR 을 띄웠어요"

    fun decide(
        action: FixAction,
        maker: LinkTargets.Maker,
        searchPlaceWorks: Boolean = SEARCH_PLACE_WORKS,
    ): LinkDecision {
        val keyword = action.searchKeyword
        if (keyword != null && searchPlaceWorks) {
            return LinkDecision(LinkKind.NAVI, keyword, null, blockedWhileDriving = false)
        }
        val url = when (action) {
            FixAction.REPAIR_SHOP -> LinkTargets.repairUrl(maker)
            FixAction.PARTS -> LinkTargets.partsUrl()
            FixAction.CHARGE, FixAction.FUEL -> null
        }
        return if (url != null) {
            LinkDecision(LinkKind.QR, null, url, blockedWhileDriving = true)
        } else {
            LinkDecision(LinkKind.NONE, null, null, blockedWhileDriving = false)
        }
    }
}
