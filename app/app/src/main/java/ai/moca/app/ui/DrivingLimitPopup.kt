package ai.moca.app.ui

import ai.moca.app.MainActivity
import ai.moca.app.R
import ai.pleos.ds.PleosPopup

/**
 * 주행 중에 상태관리·대전하기·설정으로 들어가려 할 때의 안내(최종정리 4장 6번).
 * 화면은 바꾸지 않고, 음성으로 묻는 길만 연다.
 */
object DrivingLimitPopup {

    fun show(activity: MainActivity, openVoice: () -> Unit) {
        PleosPopup.Builder(activity)
            .description(activity.getString(R.string.c08_driving_limited))
            .confirm(activity.getString(R.string.c09_ask_by_voice)) { openVoice() }
            .cancel(activity.getString(R.string.common_close)) { }
            .show()
    }
}
