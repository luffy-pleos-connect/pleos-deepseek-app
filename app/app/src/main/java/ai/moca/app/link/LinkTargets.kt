package ai.moca.app.link

import java.net.URI

/**
 * QR 이 가리키는 공식 주소.
 * 출처는 research/09_연결_주소/연결_주소.md 의 「MOCA 가 쓸 것」이다.
 * 충전소·주유소의 공식 주소는 그 문서에 없다. 주소를 만들지 않는다.
 */
object LinkTargets {
    enum class Maker { HYUNDAI, KIA, GENESIS, UNKNOWN }

    /** 현대 정비 예약·블루핸즈 찾기. 확인일 2026-11-11. */
    const val HYUNDAI_REPAIR =
        "https://www.hyundai.com/kr/ko/service-membership/service-network/service-reservation-search"

    /** 기아 오토큐 검색(휴대폰). 확인일 2026-11-11. */
    const val KIA_REPAIR =
        "https://m.members.kia.com/kr/view/web/knet/asn_prct/knet_asn_prct_index.do"

    /** 제네시스 서비스 네트워크 찾기. 확인일 2026-11-11. */
    const val GENESIS_REPAIR =
        "https://www.genesis.com/kr/ko/members/vehicle-management-service/genesis-service-network.html"

    /** 현대모비스 순정 부품 간단 검색. 확인일 2026-11-11. */
    const val PARTS = "https://www.mobis-as.com/simple_search_part.do"

    fun repairUrl(maker: Maker): String = when (maker) {
        Maker.HYUNDAI, Maker.UNKNOWN -> HYUNDAI_REPAIR
        Maker.KIA -> KIA_REPAIR
        Maker.GENESIS -> GENESIS_REPAIR
    }

    fun partsUrl(): String = PARTS

    fun domainOf(url: String): String = URI(url).host ?: url
}
