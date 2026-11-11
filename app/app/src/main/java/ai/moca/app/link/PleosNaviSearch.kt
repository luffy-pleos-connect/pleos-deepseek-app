package ai.moca.app.link

import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 내비게이션 검색 화면을 연다.
 * S3 에서 리스너 콜백은 없었고, onConnected 다음 requestNavigationScreen 이
 * 예외 없이 돌아온 것이 응답이었다. 그 시험은 헬퍼를 살려 둔 상태에서 검색이 끝났으므로
 * 성공한 연결은 프로세스 동안 유지한다.
 * 실패는 onFailed, 예외, 5초 안에 onConnected 가 없는 경우다.
 */
internal object PleosNaviSearch {
    private const val CONNECT_TIMEOUT_MS = 5_000L

    private data class Pending(val keyword: String, val onDone: (Boolean) -> Unit)

    private val handler = Handler(Looper.getMainLooper())
    private val pending = mutableListOf<Pending>()
    private var navi: NaviHelper? = null
    private var connected = false
    private var connecting = false

    fun open(context: Context, keyword: String, onDone: (Boolean) -> Unit) {
        val appContext = context.applicationContext
        handler.post { openOnMain(appContext, keyword, onDone) }
    }

    private fun openOnMain(context: Context, keyword: String, onDone: (Boolean) -> Unit) {
        val current = navi
        if (current != null && connected) {
            onDone(request(current, keyword))
            return
        }
        pending += Pending(keyword, onDone)
        if (connecting) return
        connecting = true
        val created = current ?: NaviHelper(context).also { navi = it }
        val finished = AtomicBoolean(false)
        lateinit var listener: NaviHelperEventListener
        val timeout = Runnable { complete(created, listener, finished, false) }
        listener = object : NaviHelperEventListener {
            override fun onError(errorCode: ai.pleos.playground.navi.constants.NaviErrorCode) {
                complete(created, listener, finished, false)
            }
        }
        handler.postDelayed(timeout, CONNECT_TIMEOUT_MS)
        try {
            created.initialize(
                onConnected = {
                    if (finished.get()) return@initialize
                    connected = true
                    runCatching { created.addListener(listener) }
                    handler.removeCallbacks(timeout)
                    complete(created, listener, finished, true)
                },
                onFailed = {
                    handler.removeCallbacks(timeout)
                    complete(created, listener, finished, false)
                },
            )
        } catch (e: Exception) {
            handler.removeCallbacks(timeout)
            complete(created, listener, finished, false)
        }
    }

    private fun request(helper: NaviHelper, keyword: String): Boolean = try {
        helper.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword))
        true
    } catch (e: Exception) {
        false
    }

    private fun complete(
        helper: NaviHelper,
        listener: NaviHelperEventListener,
        finished: AtomicBoolean,
        connectedOk: Boolean,
    ) {
        if (!finished.compareAndSet(false, true)) return
        connecting = false
        runCatching { helper.removeListener(listener) }
        if (!connectedOk) {
            connected = false
            runCatching { helper.release() }
            if (navi === helper) navi = null
        }
        val batch = pending.toList()
        pending.clear()
        val live = navi
        for (item in batch) {
            val opened = connectedOk && live != null && request(live, item.keyword)
            item.onDone(opened)
        }
    }
}
