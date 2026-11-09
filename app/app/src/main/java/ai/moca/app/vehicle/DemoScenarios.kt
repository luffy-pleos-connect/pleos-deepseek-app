package ai.moca.app.vehicle

/**
 * 데모 시나리오 D0~D6 (계획 10장 표). 실제 차량 값이 아니다.
 * 모든 데모 화면에는 "데모 데이터" 배지가 붙는다.
 *
 * 값의 의미는 PleosVehicleSource 의 읽기 키와 같다.
 *  - oil_engine: 0=정상, 1=LOW, 2=CRITICALLY_LOW, 3=HIGH
 *  - tire_low: 저압 바퀴 수
 *  - soc: 배터리 %
 *  - telltales: 켜진 경고등 수
 */
enum class DemoScenario(
    val id: String,
    val label: String,
    /** 점검 결과 기대값(계획 10장). 화면·테스트가 이 값을 쓴다. */
    val expectedGrade: String,
    val expectedTotal: Int,
) {
    D0("D0", "정상, 연료 62%", "A", 85),
    D1("D1", "엔진오일 부족", "A", 80),
    D2("D2", "TPMS 노랑 + 바퀴 2개 저압", "A", 80),
    D3("D3", "ABS 빨강 + TPMS 노랑 + 바퀴 1개 저압", "B", 75),
    D4("D4", "배터리 9%", "A", 84),
    D5("D5", "엔진오일 부족 + TPMS 노랑 + 바퀴 1개 저압", "B", 76),
    D6("D6", "타이어 미지원", "A", 85),
    ;

    fun readings(): LinkedHashMap<String, ItemReading> {
        val map = LinkedHashMap<String, ItemReading>()
        fun put(key: String, value: Double?, supported: Boolean = true) {
            map[key] = ItemReading(key, supported, value)
        }

        put(PleosVehicleSource.KEY_BRAKE_FLUID, 0.0)
        put(PleosVehicleSource.KEY_WASHER, 0.0)
        put(PleosVehicleSource.KEY_POWER_DOWN, 0.0)
        put(PleosVehicleSource.KEY_TIRE_KPA, 235.0)

        when (this) {
            D0 -> {
                put(PleosVehicleSource.KEY_OIL_ENGINE, 0.0)
                put(PleosVehicleSource.KEY_TIRE_LOW, 0.0)
                put(PleosVehicleSource.KEY_SOC, 62.0)
                put(PleosVehicleSource.KEY_TELLTALES, 0.0)
            }
            D1 -> {
                put(PleosVehicleSource.KEY_OIL_ENGINE, 1.0)
                put(PleosVehicleSource.KEY_TIRE_LOW, 0.0)
                put(PleosVehicleSource.KEY_SOC, 62.0)
                put(PleosVehicleSource.KEY_TELLTALES, 0.0)
            }
            D2 -> {
                put(PleosVehicleSource.KEY_OIL_ENGINE, 0.0)
                put(PleosVehicleSource.KEY_TIRE_LOW, 2.0)
                put(PleosVehicleSource.KEY_SOC, 62.0)
                put(PleosVehicleSource.KEY_TELLTALES, 1.0) // TPMS 노랑
            }
            D3 -> {
                put(PleosVehicleSource.KEY_OIL_ENGINE, 0.0)
                put(PleosVehicleSource.KEY_TIRE_LOW, 1.0)
                put(PleosVehicleSource.KEY_SOC, 62.0)
                put(PleosVehicleSource.KEY_TELLTALES, 2.0) // ABS 빨강 + TPMS 노랑
            }
            D4 -> {
                put(PleosVehicleSource.KEY_OIL_ENGINE, 0.0)
                put(PleosVehicleSource.KEY_TIRE_LOW, 0.0)
                put(PleosVehicleSource.KEY_SOC, 9.0)
                put(PleosVehicleSource.KEY_TELLTALES, 0.0)
            }
            D5 -> {
                put(PleosVehicleSource.KEY_OIL_ENGINE, 1.0)
                put(PleosVehicleSource.KEY_TIRE_LOW, 1.0)
                put(PleosVehicleSource.KEY_SOC, 62.0)
                put(PleosVehicleSource.KEY_TELLTALES, 1.0)
            }
            D6 -> {
                put(PleosVehicleSource.KEY_OIL_ENGINE, 0.0)
                put(PleosVehicleSource.KEY_TIRE_LOW, null, supported = false)
                put(PleosVehicleSource.KEY_TIRE_KPA, null, supported = false)
                put(PleosVehicleSource.KEY_SOC, 62.0)
                put(PleosVehicleSource.KEY_TELLTALES, 0.0)
            }
        }
        return map
    }

    fun warnings(): WarningSummary = when (this) {
        D3 -> WarningSummary(redCount = 1, yellowCount = 1, total = 2, isDemo = true)
        D2, D5 -> WarningSummary(redCount = 0, yellowCount = 1, total = 1, isDemo = true)
        else -> WarningSummary(0, 0, 0, isDemo = true)
    }

    /** 계획 10장의 고정 상대. */
    fun opponentNickname(): String = when (this) {
        D0 -> "달리는곰"
        D1 -> "세차는사랑"
        D2 -> "출근길요정"
        D3 -> "라이벌드라이버"
        D4 -> "주말캠퍼"
        D5 -> "경고등수집가"
        D6 -> "퇴근길질주"
    }

    companion object {
        fun byId(id: String?): DemoScenario = entries.firstOrNull { it.id == id } ?: D3
    }
}

object DemoScenarios {

    fun source(scenarioId: String?): VehicleSource = object : VehicleSource {
        private val scenario = DemoScenario.byId(scenarioId)
        private var watcher: ((WarningSummary) -> Unit)? = null

        override suspend fun read(): VehicleSnapshot =
            VehicleSnapshot(scenario.readings(), System.currentTimeMillis(), isDemo = true)

        override fun cachedWarnings(): WarningSummary = scenario.warnings()

        override fun startWatchingWarnings(onChange: (WarningSummary) -> Unit) {
            watcher = onChange
            onChange(scenario.warnings())
        }

        override fun stopWatchingWarnings() {
            watcher = null
        }
    }
}
