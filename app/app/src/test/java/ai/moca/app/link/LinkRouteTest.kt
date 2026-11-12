package ai.moca.app.link

import moca.core.FixAction
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkRouteTest {
    @Test
    fun `검색이 되면 정비소 충전 주유는 내비이고 부품은 QR 이다`() {
        assertEquals(LinkKind.NAVI, decision(FixAction.REPAIR_SHOP, true).kind)
        assertEquals("정비소", decision(FixAction.REPAIR_SHOP, true).keyword)
        assertEquals(LinkKind.NAVI, decision(FixAction.CHARGE, true).kind)
        assertEquals("전기차 충전소", decision(FixAction.CHARGE, true).keyword)
        assertEquals(LinkKind.NAVI, decision(FixAction.FUEL, true).kind)
        assertEquals("주유소", decision(FixAction.FUEL, true).keyword)
        val parts = decision(FixAction.PARTS, true)
        assertEquals(LinkKind.QR, parts.kind)
        assertEquals(LinkTargets.PARTS, parts.url)
    }

    @Test
    fun `타이어 문제는 정비소가 아니라 타이어로 검색한다`() {
        val tire = decision(FixAction.TIRE_SHOP, true)
        assertEquals(LinkKind.NAVI, tire.kind)
        assertEquals("타이어", tire.keyword)
        assertFalse(tire.blockedWhileDriving)
        // 내비 검색이 안 되면 정비소처럼 제조사 정비 주소로 넘어간다.
        assertEquals(LinkKind.QR, decision(FixAction.TIRE_SHOP, false).kind)
    }

    @Test
    fun `검색이 안 되면 정비소와 부품은 QR 이고 충전 주유는 주소가 없다`() {
        val repair = decision(FixAction.REPAIR_SHOP, false)
        assertEquals(LinkKind.QR, repair.kind)
        assertEquals(LinkTargets.HYUNDAI_REPAIR, repair.url)
        assertEquals(LinkKind.QR, decision(FixAction.PARTS, false).kind)
        val charge = decision(FixAction.CHARGE, false)
        assertEquals(LinkKind.NONE, charge.kind)
        assertNull(charge.url)
        val fuel = decision(FixAction.FUEL, false)
        assertEquals(LinkKind.NONE, fuel.kind)
        assertNull(fuel.url)
    }

    @Test
    fun `주행 중에는 QR 만 막히고 내비는 열린다`() {
        assertFalse(decision(FixAction.REPAIR_SHOP, true).blockedWhileDriving)
        assertFalse(decision(FixAction.CHARGE, true).blockedWhileDriving)
        assertFalse(decision(FixAction.FUEL, true).blockedWhileDriving)
        assertTrue(decision(FixAction.PARTS, true).blockedWhileDriving)
        assertTrue(decision(FixAction.REPAIR_SHOP, false).blockedWhileDriving)
        assertFalse(decision(FixAction.CHARGE, false).blockedWhileDriving)
    }

    @Test
    fun `기아와 제네시스 정비 QR 은 그 제조사 주소다`() {
        assertEquals(
            LinkTargets.KIA_REPAIR,
            LinkRoute.decide(FixAction.REPAIR_SHOP, LinkTargets.Maker.KIA, false).url,
        )
        assertEquals(
            LinkTargets.GENESIS_REPAIR,
            LinkRoute.decide(FixAction.REPAIR_SHOP, LinkTargets.Maker.GENESIS, false).url,
        )
    }

    @Test
    fun `기본 길은 시험으로 확인한 검색 가능 여부를 따른다`() {
        val viaDefault = LinkRoute.decide(FixAction.REPAIR_SHOP, LinkTargets.Maker.UNKNOWN)
        val viaFlag = LinkRoute.decide(
            FixAction.REPAIR_SHOP,
            LinkTargets.Maker.UNKNOWN,
            LinkRoute.SEARCH_PLACE_WORKS,
        )
        assertEquals(viaFlag, viaDefault)
    }

    @Test
    fun `결과 JSON 은 ok 와 무엇을 열었는지를 담는다`() {
        val opened = JSONObject(
            FixLinks.Result(FixLinks.Code.QR_SHOWN, LinkRoute.MSG_QR_SHOWN, fellBackFromNavi = true).toJson(),
        )
        assertTrue(opened.getBoolean("ok"))
        assertEquals("QR_SHOWN", opened.getString("result"))
        assertTrue(opened.getBoolean("fallback"))

        val blocked = JSONObject(
            FixLinks.Result(FixLinks.Code.BLOCKED_DRIVING, LinkRoute.MSG_BLOCKED_DRIVING).toJson(),
        )
        assertFalse(blocked.getBoolean("ok"))
        assertEquals("BLOCKED_DRIVING", blocked.getString("result"))
        assertEquals("정차한 뒤에 볼 수 있어요", blocked.getString("message"))

        val failed = JSONObject(
            FixLinks.Result(FixLinks.Code.FAILED, LinkRoute.MSG_FIND_IN_NAVI).toJson(),
        )
        assertFalse(failed.getBoolean("ok"))
        assertEquals("내비게이션에서 직접 찾아 주세요", failed.getString("message"))
    }

    private fun decision(action: FixAction, searchPlaceWorks: Boolean) =
        LinkRoute.decide(action, LinkTargets.Maker.UNKNOWN, searchPlaceWorks)
}
