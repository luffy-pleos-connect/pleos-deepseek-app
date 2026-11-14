package ai.moca.app.battle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 대전·월간랭킹 화면이 문장을 만들 때 쓰는 작은 규칙(조사, AI 문장의 숫자 검사). */
class BattleTextHelpersTest {

    @Test
    fun `목적격 조사는 받침이 있으면 을 없으면 를 붙인다`() {
        assertEquals("을", objectParticle("ABS 경고등"))
        assertEquals("를", objectParticle("에어백 전개"))
        assertEquals("을", objectParticle("브레이크액 부족"))
        assertEquals("를", objectParticle("배터리 9%"))
        assertEquals("를", objectParticle("타이어 저압 1개"))
        assertEquals("을", objectParticle("ABS 경고등, 브레이크액"))
    }

    @Test
    fun `서버가 쓴 문장은 확인된 값에 있는 숫자만 쓸 때 받아들인다`() {
        val known = listOf(3, 2, 1, 22, 9, 13)
        assertTrue(CareSummaryBuilder.usesOnly("이번 달 3일 점검했고 2일 연속이에요. 1위와는 22점 차이예요.", known))
        assertTrue(CareSummaryBuilder.usesOnly("숫자 없이 말해요", known))
        assertFalse(CareSummaryBuilder.usesOnly("이번 달 5일 점검했어요", known))
    }
}
