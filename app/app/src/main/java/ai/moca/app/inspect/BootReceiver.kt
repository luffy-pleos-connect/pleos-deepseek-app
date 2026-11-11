package ai.moca.app.inspect

import ai.moca.app.MocaApp
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/** 시동 직후, 동의가 끝난 차에만 점검을 한 번 띄운다. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? MocaApp ?: return
        val reason = BootPolicy.skipReason(app.prefs.onboarded, app.prefs.consentData)
        if (reason != null) {
            Log.i(InspectionService.TAG, "skipped $reason")
            return
        }
        ContextCompat.startForegroundService(context, Intent(context, InspectionService::class.java))
    }
}
