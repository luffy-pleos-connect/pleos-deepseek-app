package ai.moca.app.inspect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BootPolicyTest {
    @Test
    fun `동의와 첫 실행이 끝나야 점검한다`() {
        assertFalse(BootPolicy.shouldInspect(false, false))
        assertFalse(BootPolicy.shouldInspect(false, true))
        assertFalse(BootPolicy.shouldInspect(true, false))
        assertTrue(BootPolicy.shouldInspect(true, true))
    }

    @Test
    fun `건너뛰는 이유를 적는다`() {
        assertEquals("not_onboarded", BootPolicy.skipReason(false, true))
        assertEquals("no_consent", BootPolicy.skipReason(true, false))
        assertNull(BootPolicy.skipReason(true, true))
    }
}
