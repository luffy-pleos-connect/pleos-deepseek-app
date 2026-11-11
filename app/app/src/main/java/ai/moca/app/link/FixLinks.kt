package ai.moca.app.link

import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import moca.core.FixAction
import org.json.JSONObject

/**
 * 문제를 고칠 곳으로 이어 준다.
 * 제조사를 읽는 값이 앱에 없어 QR 정비 주소는 [LinkTargets.Maker.UNKNOWN](현대)을 쓴다.
 */
object FixLinks {
    enum class Code { NAVI_OPENED, QR_SHOWN, BLOCKED_DRIVING, FAILED }

    data class Result(
        val code: Code,
        val message: String,
        val fellBackFromNavi: Boolean = false,
    ) {
        fun toJson(): String = JSONObject().apply {
            val opened = code == Code.NAVI_OPENED || code == Code.QR_SHOWN
            put("ok", opened)
            put("result", code.name)
            put("message", message)
            if (fellBackFromNavi) put("fallback", true)
        }.toString()
    }

    fun start(
        activity: FragmentActivity,
        action: FixAction,
        drivingLimited: Boolean,
        onDone: (Result) -> Unit = {},
    ) {
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        fun done(result: Result) {
            if (once.compareAndSet(false, true)) onDone(result)
        }
        dispatch(activity, action, drivingLimited, fellBackFromNavi = false, ::done)
    }

    private fun dispatch(
        activity: FragmentActivity,
        action: FixAction,
        drivingLimited: Boolean,
        fellBackFromNavi: Boolean,
        onDone: (Result) -> Unit,
    ) {
        val maker = LinkTargets.Maker.UNKNOWN
        val decision = LinkRoute.decide(
            action,
            maker,
            searchPlaceWorks = if (fellBackFromNavi) false else LinkRoute.SEARCH_PLACE_WORKS,
        )
        when (decision.kind) {
            LinkKind.NAVI -> {
                val keyword = decision.keyword
                if (keyword == null) {
                    onDone(Result(Code.FAILED, LinkRoute.MSG_FIND_IN_NAVI, fellBackFromNavi))
                    return
                }
                try {
                    PleosNaviSearch.open(activity, keyword) { opened ->
                        if (opened) {
                            onDone(Result(Code.NAVI_OPENED, LinkRoute.MSG_NAVI_OPENED, fellBackFromNavi))
                        } else {
                            dispatch(activity, action, drivingLimited, fellBackFromNavi = true, onDone)
                        }
                    }
                } catch (e: Exception) {
                    dispatch(activity, action, drivingLimited, fellBackFromNavi = true, onDone)
                }
            }
            LinkKind.QR -> {
                if (drivingLimited) {
                    onDone(Result(Code.BLOCKED_DRIVING, LinkRoute.MSG_BLOCKED_DRIVING, fellBackFromNavi))
                    return
                }
                val url = decision.url
                if (url == null) {
                    onDone(Result(Code.FAILED, LinkRoute.MSG_FIND_IN_NAVI, fellBackFromNavi))
                    return
                }
                showQr(activity, action.label, url, fellBackFromNavi, onDone)
            }
            LinkKind.NONE -> onDone(Result(Code.FAILED, LinkRoute.MSG_FIND_IN_NAVI, fellBackFromNavi))
        }
    }

    private fun showQr(
        activity: FragmentActivity,
        title: String,
        url: String,
        fellBackFromNavi: Boolean,
        onDone: (Result) -> Unit,
    ) {
        activity.runOnUiThread {
            if (activity.isFinishing) {
                onDone(Result(Code.FAILED, LinkRoute.MSG_FIND_IN_NAVI, fellBackFromNavi))
                return@runOnUiThread
            }
            try {
                val dialog = android.app.Dialog(activity, ai.pleos.ds.R.style.Theme_PleosConnect_Popup)
                val root = activity.layoutInflater.inflate(ai.moca.app.R.layout.dialog_qr, null)
                root.findViewById<TextView>(ai.moca.app.R.id.qrTitle).text = title
                root.findViewById<TextView>(ai.moca.app.R.id.qrDomain).text = LinkTargets.domainOf(url)
                val sizePx = (360 * activity.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                root.findViewById<ImageView>(ai.moca.app.R.id.qrImage)
                    .setImageBitmap(QrBitmap.render(QrMatrix.encode(url), sizePx))
                root.findViewById<Button>(ai.moca.app.R.id.qrClose).setOnClickListener { dialog.dismiss() }
                dialog.setContentView(root)
                dialog.window?.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                )
                dialog.show()
                val message = if (fellBackFromNavi) LinkRoute.MSG_NAVI_FAILED_QR else LinkRoute.MSG_QR_SHOWN
                onDone(Result(Code.QR_SHOWN, message, fellBackFromNavi))
            } catch (e: Exception) {
                onDone(Result(Code.FAILED, LinkRoute.MSG_FIND_IN_NAVI, fellBackFromNavi))
            }
        }
    }
}
