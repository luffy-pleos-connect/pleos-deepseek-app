package ai.moca.app.link

import androidx.fragment.app.FragmentActivity
import ai.moca.app.MocaApp
import ai.moca.app.R
import ai.moca.app.nav.PlaceKind
import moca.core.FixAction
import org.json.JSONObject

/**
 * 문제를 고칠 곳으로 이어 준다. 내비 갈래 하나만 남는다(최종정리 5장 6·7·8번).
 * 용품 구매 QR 은 상태관리 화면이 그 줄 안에 그림으로 보여 준다. 여기서 QR 을 만들지 않는다.
 */
object FixLinks {
    enum class Code { NAVI_OPENED, FAILED }

    data class Result(
        val code: Code,
        val message: String,
        val demoData: Boolean = false,
    ) {
        fun toJson(): String = JSONObject().apply {
            put("ok", code == Code.NAVI_OPENED)
            put("result", code.name)
            put("message", message)
            if (demoData) put("demo", true)
        }.toString()
    }

    fun start(
        activity: FragmentActivity,
        action: FixAction,
        onDone: (Result) -> Unit = {},
    ) {
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        fun done(result: Result) {
            if (once.compareAndSet(false, true)) onDone(result)
        }
        val kind = placeKindOf(action)
        val app = activity.application as? MocaApp
        if (kind == null || app == null) {
            done(Result(Code.FAILED, activity.getString(R.string.link_qr_in_status)))
            return
        }
        val result = app.navi.navigateTo(kind, demo = app.prefs.demoEnabled)
        done(
            Result(
                code = if (result.ok) Code.NAVI_OPENED else Code.FAILED,
                message = result.message,
                demoData = result.demoData,
            ),
        )
    }

    /** 내비로 갈 [FixAction] 을 장소 종류로 옮긴다. 부품은 내비 대상이 아니라 상태관리 QR 로 간다. */
    private fun placeKindOf(action: FixAction): PlaceKind? = when (action) {
        FixAction.REPAIR_SHOP -> PlaceKind.REPAIR_SHOP
        FixAction.TIRE_SHOP -> PlaceKind.TIRE_SHOP
        FixAction.CHARGE -> PlaceKind.CHARGING_STATION
        FixAction.FUEL -> PlaceKind.GAS_STATION
        FixAction.PARTS -> null
    }
}
