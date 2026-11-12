package ai.moca.app.net

import ai.moca.app.score.ScoreBridge
import ai.moca.app.vehicle.DemoScenario
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 서버 POST /snapshots 본문이 서버 검증 규칙(server/validate.mjs)을 지키는지 본다. */
class SnapshotPayloadTest {
    private fun eval(s: DemoScenario) = ScoreBridge.evaluate(s.readings(), s.warnings(), true)

    @Test
    fun `D3 요약은 서버가 받는 모양이다`() {
        val body = SnapshotPayload.build("device-aaaa-0001", "MOCA1", eval(DemoScenario.D3), isDemo = true, inspectedAt = 1_700_000_000_000L)!!
        assertEquals(setOf("deviceId", "nickname", "grade", "total", "categories", "isDemo", "inspectedAt"), body.keys().asSequence().toSet())
        assertEquals("B", body.getString("grade"))
        assertEquals(75, body.getInt("total"))
        val categories = body.getJSONObject("categories")
        assertEquals(setOf("power", "brake", "tire", "electric", "safety"), categories.keys().asSequence().toSet())
        for (key in categories.keys()) {
            val value = categories.get(key)
            assertTrue("$key=$value", value == JSONObject.NULL || (value is Int && value in 0..100))
        }
        assertEquals(50, categories.getInt("brake"))
        assertTrue(body.getBoolean("isDemo"))
    }

    @Test
    fun `등급이 없으면 올리지 않는다`() {
        assertNull(SnapshotPayload.build("device-aaaa-0001", "MOCA1", null, isDemo = false, inspectedAt = 1L))
    }
}
