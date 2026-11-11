package ai.moca.app.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkTargetsTest {
    @Test
    fun `제조사별 정비 주소와 모르는 제조사는 현대다`() {
        assertEquals(LinkTargets.HYUNDAI_REPAIR, LinkTargets.repairUrl(LinkTargets.Maker.HYUNDAI))
        assertEquals(LinkTargets.KIA_REPAIR, LinkTargets.repairUrl(LinkTargets.Maker.KIA))
        assertEquals(LinkTargets.GENESIS_REPAIR, LinkTargets.repairUrl(LinkTargets.Maker.GENESIS))
        assertEquals(LinkTargets.HYUNDAI_REPAIR, LinkTargets.repairUrl(LinkTargets.Maker.UNKNOWN))
    }

    @Test
    fun `부품 주소와 정비 주소는 https 로 시작한다`() {
        val urls = listOf(
            LinkTargets.repairUrl(LinkTargets.Maker.HYUNDAI),
            LinkTargets.repairUrl(LinkTargets.Maker.KIA),
            LinkTargets.repairUrl(LinkTargets.Maker.GENESIS),
            LinkTargets.repairUrl(LinkTargets.Maker.UNKNOWN),
            LinkTargets.partsUrl(),
        )
        for (url in urls) {
            assertTrue(url, url.startsWith("https://"))
        }
    }

    @Test
    fun `도메인은 호스트만 보여 준다`() {
        assertEquals("www.hyundai.com", LinkTargets.domainOf(LinkTargets.HYUNDAI_REPAIR))
        assertEquals("m.members.kia.com", LinkTargets.domainOf(LinkTargets.KIA_REPAIR))
        assertEquals("www.genesis.com", LinkTargets.domainOf(LinkTargets.GENESIS_REPAIR))
        assertEquals("www.mobis-as.com", LinkTargets.domainOf(LinkTargets.PARTS))
    }
}
