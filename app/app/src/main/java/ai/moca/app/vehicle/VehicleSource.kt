package ai.moca.app.vehicle

/**
 * 점검 항목 하나의 결과.
 * - supported=false : 이 차가 항목 자체를 지원하지 않는다 (capability 없음)
 * - value=null      : 지원은 하지만 이번에 읽지 못했다 (시간 초과·예외)
 */
data class ItemReading(
    val key: String,
    val supported: Boolean,
    val value: Double?,
    val note: String? = null,
)

/** 차량에서 읽은 원본값 묶음. 점수 계산은 :core 가 한다. */
data class VehicleSnapshot(
    val items: Map<String, ItemReading>,
    val readAtMillis: Long,
    val isDemo: Boolean,
)

/**
 * Vehicle SDK 접근 경계. 실제 구현과 데모 구현이 같은 인터페이스를 쓴다.
 * 모든 읽기는 항목당 제한 시간(1.5초)을 두고, 넘기면 value=null 로 남긴다.
 */
interface VehicleSource {
    /** 한 번 점검분을 읽는다. 실패한 항목도 결과에 남긴다. */
    suspend fun read(): VehicleSnapshot

    /** 화면 배지용 경고 요약. 리스너 기반 구현은 캐시 값을 즉시 돌려준다. */
    fun cachedWarnings(): WarningSummary

    /** 경고등 리스너를 붙인다. 데모에서는 고정값을 돌려준다. */
    fun startWatchingWarnings(onChange: (WarningSummary) -> Unit)

    fun stopWatchingWarnings()
}

/** 홈 배지에 쓰는 요약. redCount > 0 이면 빨간 배지. */
data class WarningSummary(
    val redCount: Int,
    val yellowCount: Int,
    val total: Int,
    val isDemo: Boolean,
) {
    companion object {
        fun empty(isDemo: Boolean = false) = WarningSummary(0, 0, 0, isDemo)
    }
}
