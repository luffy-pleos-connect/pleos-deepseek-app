package moca.core

import java.time.LocalDate

data class DemoScenario(
    val id: String,
    val title: String,
    val snapshot: VehicleSnapshot,
    val opponent: String,
    val expectedCare: Int,
    val expectedOverall: Double,
    val expectedGrade: Grade,
    val expectedDisplayed: Int,
)

object Demo {
    val ANCHOR: LocalDate = LocalDate.of(2026, 11, 9)

    const val CARE_TODAY = 53

    private fun normalVehicle() = VehicleSnapshot.normal().copy(fuelPercent = 62.0)

    private fun telltale(telltale: Telltale, color: Color) = TelltaleState(telltale, color)

    fun history(today: LocalDate = ANCHOR): List<Inspection> =
        (7 downTo 1).map { step ->
            Inspection(
                Profile.DEMO,
                today.minusDays(26L + step.toLong() * 2),
                normalVehicle(),
            )
        }

    fun scenarios(today: LocalDate = ANCHOR): List<Pair<DemoScenario, List<Inspection>>> {
        val base = history(today)
        return listOf(
            scenario(
                "D0", "정상, 연료 62%",
                normalVehicle(),
                "달리는곰", 85.9, Grade.A, 85,
                base, today,
            ),
            scenario(
                "D1", "엔진오일 부족",
                normalVehicle().copy(engineOil = FluidLevel.LOW),
                "세차는사랑", 80.3, Grade.A, 80,
                base, today,
            ),
            scenario(
                "D2", "TPMS 노랑 + 바퀴 2개 저압",
                normalVehicle().copy(
                    lowTireWheels = setOf(WheelArea.FL, WheelArea.RR),
                    telltales = listOf(telltale(Telltale.TPMS, Color.YELLOW)),
                ),
                "출근길요정", 80.3, Grade.A, 80,
                base, today,
            ),
            scenario(
                "D3", "ABS 빨강 + TPMS 노랑 + 바퀴 1개 저압",
                normalVehicle().copy(
                    lowTireWheels = setOf(WheelArea.FL),
                    telltales = listOf(telltale(Telltale.ABS, Color.RED), telltale(Telltale.TPMS, Color.YELLOW)),
                ),
                "라이벌드라이버", 75.4, Grade.B, 75,
                base, today,
            ),
            scenario(
                "D4", "배터리 9%",
                normalVehicle().copy(batteryPercent = 9.0),
                "주말캠퍼", 84.5, Grade.A, 84,
                base, today,
            ),
            scenario(
                "D5", "엔진오일 부족 + TPMS 노랑 + 바퀴 1개 저압",
                normalVehicle().copy(
                    engineOil = FluidLevel.LOW,
                    lowTireWheels = setOf(WheelArea.FL),
                    telltales = listOf(telltale(Telltale.TPMS, Color.YELLOW)),
                ),
                "경고등수집가", 76.8, Grade.B, 76,
                base, today,
            ),
            scenario(
                "D6", "타이어 미지원",
                normalVehicle().copy(unsupported = setOf(Item.TIRE)),
                "퇴근길질주", 85.9, Grade.A, 85,
                base, today,
            ),
        )
    }

    private fun scenario(
        id: String,
        title: String,
        snapshot: VehicleSnapshot,
        opponent: String,
        overall: Double,
        grade: Grade,
        displayed: Int,
        base: List<Inspection>,
        today: LocalDate,
    ): Pair<DemoScenario, List<Inspection>> {
        val record = Inspection(Profile.DEMO, today, snapshot, seq = base.size.toLong(), scenarioId = id)
        return DemoScenario(id, title, snapshot, opponent, CARE_TODAY, overall, grade, displayed) to (base + record)
    }

    fun recordsOf(id: String, today: LocalDate = ANCHOR): List<Inspection> {
        val entry = scenarios(today).firstOrNull { it.first.id == id }
            ?: throw IllegalArgumentException("알 수 없는 데모 시나리오: $id")
        return entry.second
    }

    fun snapshotOf(id: String): VehicleSnapshot {
        val entry = scenarios().firstOrNull { it.first.id == id }
            ?: throw IllegalArgumentException("알 수 없는 데모 시나리오: $id")
        return entry.first.snapshot
    }

    fun resolvedD3(today: LocalDate = ANCHOR): List<Inspection> {
        val base = recordsOf("D3", today)
        val fixed = VehicleSnapshot.normal()
            .copy(lowTireWheels = setOf(WheelArea.FL), telltales = listOf(telltale(Telltale.TPMS, Color.YELLOW)))
        return base + Inspection(Profile.DEMO, today, fixed, seq = base.size.toLong(), scenarioId = "D3-fixed")
    }

    fun careOf(id: String, today: LocalDate = ANCHOR): CareResult =
        Care.evaluate(recordsOf(id, today), Profile.DEMO, today)

    fun evaluateOf(id: String, care: Int = CARE_TODAY): Evaluation =
        Scoring.evaluate(snapshotOf(id), care)
}
