package ai.moca.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 최종정리 3장 0번: 닉네임은 한글·영문·숫자 2~10자. 비우면 «드라이버» + 숫자 4자리.
 * lead 가 쓴 테스트다. 워커는 이 파일을 고치지 않는다.
 *
 * 약속(ai.moca.app.data):
 *   object NicknameRule {
 *       fun isValid(text: String): Boolean
 *       fun resolve(typed: String, fourDigits: () -> Int = { (1000..9999).random() }): String?
 *           비어 있으면(공백만 포함) «드라이버» + fourDigits(), 규칙에 맞으면 앞뒤 공백을 뗀 글자, 맞지 않으면 null
 *   }
 */
class NicknameRuleTest {

    @Test
    fun `한글 영문 숫자 2자에서 10자까지 받는다`() {
        for (ok in listOf("드라", "ab", "A1", "모카드라이버", "가나다라마바사아자차", "moca2026", "드라이버1234")) {
            assertTrue(ok, NicknameRule.isValid(ok))
        }
    }

    @Test
    fun `짧거나 길거나 다른 글자가 있으면 받지 않는다`() {
        for (bad in listOf("", "a", "가", "가나다라마바사아자차카", "abcdefghijk", "나 야", "ab!", "모카_1", "a-b", "ㅋㅋ", "😀😀", "ab\n")) {
            assertFalse(bad, NicknameRule.isValid(bad))
        }
    }

    @Test
    fun `비우면 드라이버와 숫자 네 자리를 만든다`() {
        assertEquals("드라이버4821", NicknameRule.resolve("") { 4821 })
        assertEquals("드라이버1000", NicknameRule.resolve("   ") { 1000 })
        assertTrue(NicknameRule.isValid(NicknameRule.resolve("")!!))
        assertTrue(Regex("드라이버\\d{4}").matches(NicknameRule.resolve("")!!))
    }

    @Test
    fun `맞는 닉네임은 앞뒤 공백만 떼고 그대로 쓴다`() {
        assertEquals("모카", NicknameRule.resolve("  모카 ") { 1 })
        assertEquals("moca1", NicknameRule.resolve("moca1") { 1 })
    }

    @Test
    fun `맞지 않는 닉네임은 만들지 않는다`() {
        assertNull(NicknameRule.resolve("a") { 1 })
        assertNull(NicknameRule.resolve("나 야") { 1 })
        assertNull(NicknameRule.resolve("가나다라마바사아자차카") { 1 })
    }
}
