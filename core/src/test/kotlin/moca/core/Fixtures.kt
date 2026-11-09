package moca.core

import java.time.LocalDate

object Fx {
    fun day(text: String): LocalDate = LocalDate.parse(text)

    fun red(t: Telltale): TelltaleState = TelltaleState(t, Color.RED)

    fun yellow(t: Telltale): TelltaleState = TelltaleState(t, Color.YELLOW)

    fun insp(
        date: String,
        snapshot: VehicleSnapshot,
        profile: Profile = Profile.REAL,
        seq: Long = 0L,
        scenarioId: String? = null,
    ): Inspection = Inspection(profile, day(date), snapshot, seq, scenarioId)

    fun consecutive(start: String, days: Int, profile: Profile = Profile.REAL): List<Inspection> {
        val first = day(start)
        return (0 until days).map { insp(first.plusDays(it.toLong()).toString(), VehicleSnapshot.normal(), profile) }
    }

    fun cat(snapshot: VehicleSnapshot, category: Category): Int? = Scoring.categories(snapshot)[category]

    fun evaluation(
        power: Int? = 100,
        brake: Int? = 100,
        tire: Int? = 100,
        electric: Int? = 100,
        safety: Int? = 100,
        care: Int? = 50,
        redCap: Boolean = false,
    ): Evaluation = Scoring.evaluate(
        mapOf(
            Category.POWER to power,
            Category.BRAKE_STEERING to brake,
            Category.TIRE to tire,
            Category.ELECTRIC to electric,
            Category.SAFETY to safety,
        ),
        care,
        redCap,
    )
}
