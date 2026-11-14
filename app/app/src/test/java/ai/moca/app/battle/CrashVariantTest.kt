package ai.moca.app.battle

import moca.core.BattleSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 대전 결과 → 충돌 영상 종류와 부서진 차 그림. 무승부는 둘 다 멀쩡하다. */
class CrashVariantTest {

    @Test
    fun `승패에 맞는 영상을 고른다`() {
        assertEquals(CrashVariant.MINE_WINS, CrashVariant.of(BattleSide.MINE))
        assertEquals(CrashVariant.THEIRS_WINS, CrashVariant.of(BattleSide.THEIRS))
        assertEquals(CrashVariant.DRAW, CrashVariant.of(BattleSide.DRAW))
    }

    @Test
    fun `진 쪽만 부서진 그림을 보인다`() {
        assertTrue(CrashVariant.MINE_WINS.theirsWrecked)
        assertFalse(CrashVariant.MINE_WINS.mineWrecked)
        assertTrue(CrashVariant.THEIRS_WINS.mineWrecked)
        assertFalse(CrashVariant.THEIRS_WINS.theirsWrecked)
        assertFalse(CrashVariant.DRAW.mineWrecked)
        assertFalse(CrashVariant.DRAW.theirsWrecked)
    }
}
