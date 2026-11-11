package ai.moca.app.ui

import moca.core.Grade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GradeMomentTest {

    @Test
    fun `처음 보거나 등급을 모르면 없다`() {
        assertNull(GradeMoment.decide(null, null, Grade.B, 4, driving = false))
        assertNull(GradeMoment.decide("B", 6, null, 4, driving = false))
        assertNull(GradeMoment.decide("없음", 6, Grade.A, 4, driving = false))
    }

    @Test
    fun `같거나 내려가면 없다`() {
        assertNull(GradeMoment.decide("B", 6, Grade.B, 4, driving = false))
        assertNull(GradeMoment.decide("A", 4, Grade.B, 2, driving = false))
        assertNull(GradeMoment.decide("S+", 1, Grade.S, 1, driving = false))
    }

    @Test
    fun `주행 중이면 올라도 없다`() {
        assertNull(GradeMoment.decide("B", 6, Grade.A, 4, driving = true))
    }

    @Test
    fun `등급만 오르면 등급 한 줄이다`() {
        val moment = GradeMoment.decide("B", null, Grade.A, null, driving = false)
        assertEquals("B → A", moment?.text)
        assertEquals("B → A", GradeMoment.decide("B", 4, Grade.A, 4, driving = false)?.text)
        assertEquals("B → A", GradeMoment.decide("B", 4, Grade.A, 6, driving = false)?.text)
        assertEquals("B → A", GradeMoment.decide("B", null, Grade.A, 4, driving = false)?.text)
        assertEquals("D → S+", GradeMoment.decide("D", null, Grade.S_PLUS, null, driving = false)?.text)
    }

    @Test
    fun `순위도 좋아지면 순위까지 붙인다`() {
        val moment = GradeMoment.decide("B", 6, Grade.A, 4, driving = false)
        assertEquals("B → A · 6위 → 4위", moment?.text)
        assertEquals("C → S", GradeMoment.decide("C", 3, Grade.S, 3, driving = false)?.text)
    }
}
