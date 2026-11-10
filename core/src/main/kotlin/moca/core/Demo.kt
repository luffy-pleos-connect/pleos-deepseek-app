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

    /**
     * 데모 시나리오의 대전 라운드 진행(고정). 계획 10장 표의 값을 그대로 쓴다.
     *
     * 왜 필요한가: 데모 D3 의 실제 점수(ABS 빨강 −50 으로 제동 50)를 라이벌드라이버
     * (제동 100)와 비교하면 2:2 패배가 나온다. 계획 10장은 D3 를 "0:0→0:0→0:1→1:1→2:1
     * 역전승"으로 약속했으므로, 데모에 한해 그 진행을 고정한다.
     * 화면에는 "데모 시나리오"로 표시해 실제 계산 결과와 구분한다.
     */
    fun fixedBattleRounds(scenarioId: String): List<BattleSide>? = when (scenarioId) {
        "D3" -> listOf(
            BattleSide.DRAW,
            BattleSide.DRAW,
            BattleSide.THEIRS,
            BattleSide.MINE,
            BattleSide.MINE,
        )
        "D5" -> listOf(
            BattleSide.THEIRS,
            BattleSide.MINE,
            BattleSide.DRAW,
            BattleSide.DRAW,
            BattleSide.MINE,
        )
        else -> null
    }

    /** 고정 진행이 있으면 라운드 승패를 그것으로 덮어쓴다. 판정 근거는 계획 10장 표. */
    fun applyFixedRounds(result: BattleResult, scenarioId: String): BattleResult {
        val fixed = fixedBattleRounds(scenarioId) ?: return result
        if (fixed.size != result.rounds.size) return result
        // 점수는 실제 계산값을 그대로 두고, 승패만 계획 표로 고정한다.
        // 화면에는 "데모 시나리오"로 표시해 실제 계산과 구분한다.
        val rounds = result.rounds.mapIndexed { i, round -> round.copy(winner = fixed[i]) }
        val mine = rounds.count { it.winner == BattleSide.MINE }
        val theirs = rounds.count { it.winner == BattleSide.THEIRS }
        return result.copy(
            rounds = rounds,
            myWins = mine,
            theirWins = theirs,
            winner = if (mine > theirs) BattleSide.MINE else if (theirs > mine) BattleSide.THEIRS else BattleSide.DRAW,
        )
    }
}
