package ai.moca.app.ui.model

import ai.moca.app.vehicle.WarningSummary
import moca.core.Category
import moca.core.CategoryStatus
import moca.core.Color
import moca.core.Deduction
import moca.core.Evaluation
import moca.core.Item
import moca.core.Rules
import moca.core.Telltale
import moca.core.VehicleSnapshot

/** 켜진 감점 대상 경고등 한 줄. [red] 면 빨강 점, 아니면 노랑 점을 찍는다. */
data class WarningLine(val label: String, val red: Boolean)

/** 구역 한 줄. 측정하지 못했으면 [score] 가 null 이고 상태는 측정 불가다. */
data class CategoryLine(val category: Category, val score: Int?, val status: CategoryStatus)

/** 조치가 필요한 항목 한 줄. 감점은 양수로 담는다. */
data class ActionLine(val group: String, val label: String, val points: Int, val category: Category)

enum class EnergyKind { FUEL, BATTERY }

data class EnergyLine(val kind: EnergyKind, val percent: Int)

/** 앱 시작 화면이 그릴 값 전부. 화면은 이 값을 그리기만 한다. */
data class StartSummary(
    val gradeLabel: String?,
    val overall: Int?,
    val warnings: List<WarningLine>,
    val categories: List<CategoryLine>,
    val actions: List<ActionLine>,
    val energy: List<EnergyLine>,
    val unmeasured: Int,
    val hasRed: Boolean,
)

/** 최종정리 3장 1번 ①~⑥. 값은 모두 점수 엔진이 준 것이다. */
object StartSummaryBuilder {

    fun of(snapshot: VehicleSnapshot, evaluation: Evaluation): StartSummary {
        val categories = Category.entries.map { category ->
            val score = evaluation.categories[category]
            CategoryLine(category, score, CategoryStatus.of(score))
        }
        return StartSummary(
            gradeLabel = evaluation.grade?.label,
            overall = evaluation.displayedOverall,
            warnings = warningsOf(snapshot),
            categories = categories,
            actions = actionsOf(snapshot),
            energy = energyOf(snapshot),
            unmeasured = categories.count { it.score == null },
            hasRed = Rules.hasRed(snapshot),
        )
    }

    /** 감점 대상 빨강·노랑만. 빨강 먼저, 그다음 엔진 Telltale 순서. */
    private fun warningsOf(snapshot: VehicleSnapshot): List<WarningLine> {
        if (Item.TELLTALES in snapshot.unsupported) return emptyList()
        return snapshot.telltales
            .filter { it.active }
            .filter { Rules.categoryOf(it.telltale) != null }
            .filter { telltaleAvailable(snapshot, it.telltale) }
            .sortedWith(
                compareBy<moca.core.TelltaleState> { if (it.color == Color.RED) 0 else 1 }
                    .thenBy { it.telltale.ordinal },
            )
            .map { WarningLine(it.telltale.label, it.color == Color.RED) }
    }

    /** 타이어를 읽을 수 없는 차는 타이어 공기압 경고등도 감점하지 않는다(엔진 규칙과 같다). */
    private fun telltaleAvailable(snapshot: VehicleSnapshot, telltale: Telltale): Boolean =
        !(telltale == Telltale.TPMS && Item.TIRE in snapshot.unsupported)

    /** 감점이 큰 순, 같으면 구역 순서. 최대 세 줄. */
    private fun actionsOf(snapshot: VehicleSnapshot): List<ActionLine> =
        Rules.deductions(snapshot)
            .sortedWith(
                compareByDescending<Deduction> { it.points }
                    .thenBy { it.category.ordinal }
                    .thenBy { it.group },
            )
            .take(MAX_ACTIONS)
            .map { ActionLine(it.group, it.label, it.points, it.category) }

    /** 읽힌 것만. 연료 먼저, 배터리 다음. 소수점은 버린다. */
    private fun energyOf(snapshot: VehicleSnapshot): List<EnergyLine> {
        if (Item.ENERGY in snapshot.unsupported) return emptyList()
        return buildList {
            snapshot.fuelPercent?.let { add(EnergyLine(EnergyKind.FUEL, it.toInt())) }
            snapshot.batteryPercent?.let { add(EnergyLine(EnergyKind.BATTERY, it.toInt())) }
        }
    }

    private const val MAX_ACTIONS = 3
}

/** 앱 시작 화면에서 메인으로 넘어가는 규칙(최종정리 3장 3·4번). */
object StartPolicy {

    const val AUTO_ADVANCE_MS = 60_000L

    /** 주행 중이면 바로 넘어간다. 정차 중에는 빨간 경고등이 있을 때만 기다린다. */
    fun autoAdvances(summary: StartSummary, driving: Boolean): Boolean = driving || !summary.hasRed

    /** 주행 중에는 등급과 경고등만 보여 준다. */
    fun showsOnlyGradeAndWarnings(driving: Boolean): Boolean = driving

    /** 주행 중 빨간 경고등이면 정차 안내를 소리로 읽는다. */
    fun speaksStopSafely(summary: StartSummary, driving: Boolean): Boolean = driving && summary.hasRed
}

/** 마지막 점검 뒤 얼마나 지났는지. 1분 미만은 «방금». */
object AgoLabel {

    fun of(atMillis: Long, nowMillis: Long): String {
        val diff = nowMillis - atMillis
        if (diff < MINUTE) return "방금 점검 기준"
        val minutes = diff / MINUTE
        if (minutes < 60) return "${minutes}분 전 점검 기준"
        val hours = diff / HOUR
        if (hours < 24) return "${hours}시간 전 점검 기준"
        return "${diff / DAY}일 전 점검 기준"
    }

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
}

enum class BadgeTone { RED, YELLOW, NONE }

data class WarningBadge(val text: String, val tone: BadgeTone)

/** 메인 화면의 경고 배지. 감점 대상 경고등 수만 센다(최종정리 4장 1번 [제안 4]). */
object WarningBadges {

    fun of(summary: WarningSummary): WarningBadge {
        val tone = when {
            summary.redCount > 0 -> BadgeTone.RED
            summary.yellowCount > 0 -> BadgeTone.YELLOW
            else -> BadgeTone.NONE
        }
        val text = if (summary.total > 0) "주의 ${summary.total}" else "주의 없음"
        return WarningBadge(text, tone)
    }
}
