package moca.core

import java.time.LocalDate

/**
 * 2026-11-11 리뷰(docs/리뷰-20261111.md 결함 3)에서 나온 규칙.
 * 감점 원인이 사라진 것이 「해소」다. 그 항목을 이번에 읽지 못한 것은 해소가 아니다.
 */
class CareUnreadTest {
    private val day: LocalDate = LocalDate.of(2026, 11, 11)
    private val lowTire = VehicleSnapshot.normal().copy(lowTireWheels = setOf(WheelArea.FL))

    private fun care(vararg snapshots: VehicleSnapshot): Int =
        Care.evaluate(
            snapshots.mapIndexed { i, s -> Inspection(Profile.REAL, day, s, seq = i.toLong()) },
            Profile.REAL,
            day,
        ).score

    @Test
    fun unreadItemIsNotCountedAsResolved() {
        val tireUnread = VehicleSnapshot.normal().copy(unsupported = setOf(Item.TIRE))
        Assert.eq(care(lowTire), care(lowTire, tireUnread))
    }

    @Test
    fun fixedItemIsCountedAsResolved() {
        Assert.eq(care(lowTire) + Care.RESOLVED_BONUS, care(lowTire, VehicleSnapshot.normal()))
    }

    @Test
    fun unreadTelltalesDoNotResolveATelltale() {
        val abs = VehicleSnapshot.normal().copy(telltales = listOf(TelltaleState(Telltale.ABS, Color.RED)))
        val telltalesUnread = VehicleSnapshot.normal().copy(unsupported = setOf(Item.TELLTALES))
        Assert.eq(care(abs), care(abs, telltalesUnread))
    }
}
