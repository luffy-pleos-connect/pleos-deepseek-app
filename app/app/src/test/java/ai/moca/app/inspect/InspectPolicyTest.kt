package ai.moca.app.inspect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class InspectPolicyTest {

    private val day: LocalDate = LocalDate.of(2026, 11, 11)
    private val key = InspectKey(demo = false, scenarioId = "D3", day = day)

    @Test
    fun `처음이면 점검을 돈다`() {
        assertTrue(InspectPolicy.shouldRun(null, key))
    }

    @Test
    fun `데모와 시나리오와 날짜가 같으면 건너뛴다`() {
        assertFalse(InspectPolicy.shouldRun(key, key.copy()))
    }

    @Test
    fun `데모 여부가 바뀌면 다시 돈다`() {
        assertTrue(InspectPolicy.shouldRun(key, key.copy(demo = true)))
    }

    @Test
    fun `시나리오 번호가 바뀌면 다시 돈다`() {
        assertTrue(InspectPolicy.shouldRun(key, key.copy(scenarioId = "D1")))
    }

    @Test
    fun `날짜가 바뀌면 다시 돈다`() {
        assertTrue(InspectPolicy.shouldRun(key, key.copy(day = day.plusDays(1))))
    }
}
