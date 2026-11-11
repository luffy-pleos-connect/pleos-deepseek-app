package ai.moca.app.inspect

import ai.moca.app.MocaApp
import ai.moca.app.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 시동 때 화면 없이 기존 점검을 한 번 돌리고 끝낸다.
 * 알림을 띄운 뒤 길어도 30초 안에 내려간다.
 */
class InspectionService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        scope.launch {
            try {
                val app = application as MocaApp
                val reason = BootPolicy.skipReason(app.prefs.onboarded, app.prefs.consentData)
                if (reason != null) {
                    Log.i(TAG, "skipped $reason")
                } else {
                    val result = withTimeout(INSPECT_LIMIT_MS) {
                        Inspector(app).run(force = false)
                    }
                    val grade = result.evaluation?.grade?.label ?: "none"
                    Log.i(TAG, "inspected demo=${result.demo} grade=$grade")
                }
            } catch (e: Exception) {
                Log.i(TAG, "failed ${e.javaClass.simpleName}")
            } finally {
                withContext(NonCancellable) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }

    private fun promoteToForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.boot_inspecting),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cat_power)
            .setContentTitle(getString(R.string.boot_inspecting))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val TAG = "MOCA_BOOT"
        private const val CHANNEL_ID = "moca_boot"
        private const val NOTIFICATION_ID = 1001
        private const val INSPECT_LIMIT_MS = 30_000L
    }
}
