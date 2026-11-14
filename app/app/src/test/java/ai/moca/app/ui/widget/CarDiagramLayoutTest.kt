package ai.moca.app.ui.widget

import moca.core.Category
import moca.core.WheelArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2D 자동차 그림의 영역 좌표(최종정리 5장 3번). 화면 크기 520×720dp 에서 확인한다. */
class CarDiagramLayoutTest {

    private val width = 520f
    private val height = 720f

    @Test
    fun `차체 네 영역과 바퀴 네 개가 있고 서로 겹치지 않는다`() {
        val zones = CarDiagramLayout.zones(width, height)
        // 타이어 구역은 바퀴 네 개가 대신한다. 나머지 네 구역은 차체 안에 있다.
        assertEquals(8, zones.size)
        assertEquals(
            setOf(Category.POWER, Category.BRAKE_STEERING, Category.ELECTRIC, Category.SAFETY),
            zones.filter { it.wheel == null }.map { it.category }.toSet(),
        )
        assertEquals(WheelArea.entries.toSet(), zones.mapNotNull { it.wheel }.toSet())
        assertTrue(zones.filter { it.wheel != null }.all { it.category == Category.TIRE })
        for (i in zones.indices) {
            for (j in i + 1 until zones.size) {
                assertTrue(
                    "${zones[i].category}/${zones[i].wheel} 와 ${zones[j].category}/${zones[j].wheel} 가 겹친다",
                    !zones[i].rect.overlaps(zones[j].rect),
                )
            }
        }
    }

    @Test
    fun `모든 영역이 누를 수 있는 크기이고 그림 안에 들어온다`() {
        // 실제 측정 크기: 2560×1440 화면(160dpi)에서 그림 View 는 781×404dp 였다.
        val sizes = listOf(width to height, 781f to 404f, 520f to 720f)
        for ((w, h) in sizes) {
            for (zone in CarDiagramLayout.zones(w, h)) {
                assertTrue("${w}×$h ${zone.category}/${zone.wheel} 너비", zone.rect.width >= 64f)
                assertTrue("${w}×$h ${zone.category}/${zone.wheel} 높이", zone.rect.height >= 64f)
                assertTrue(zone.rect.left >= 0f && zone.rect.top >= 0f)
                assertTrue(zone.rect.right <= w && zone.rect.bottom <= h)
            }
        }
    }

    @Test
    fun `누른 점은 그 구역이고 바퀴는 타이어다`() {
        val zones = CarDiagramLayout.zones(width, height)
        for (zone in zones) {
            val centerX = (zone.rect.left + zone.rect.right) / 2f
            val centerY = (zone.rect.top + zone.rect.bottom) / 2f
            assertEquals("${zone.category}/${zone.wheel}", zone.category, CarDiagramLayout.zoneAt(width, height, centerX, centerY))
        }
        val fl = CarDiagramLayout.wheelZones(width, height).getValue(WheelArea.FL)
        assertEquals(Category.TIRE, CarDiagramLayout.zoneAt(width, height, fl.left + 1f, fl.top + 1f))
        assertNull(CarDiagramLayout.zoneAt(width, height, -5f, -5f))
        assertNull(CarDiagramLayout.zoneAt(width, height, width / 2f, height + 5f))
    }

    @Test
    fun `보닛이 동력이고 그 뒤 띠가 제동조향이다`() {
        val body = CarDiagramLayout.bodyZones(width, height)
        val power = body.getValue(Category.POWER)
        val brake = body.getValue(Category.BRAKE_STEERING)
        assertTrue(power.bottom <= brake.top)
        assertNotNull(body[Category.ELECTRIC])
        assertTrue(body.getValue(Category.SAFETY).top >= body.getValue(Category.ELECTRIC).bottom)
    }
}
