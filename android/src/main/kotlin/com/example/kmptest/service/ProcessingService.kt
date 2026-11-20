package com.example.kmptest.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.kmptest.MainActivity
import com.example.kmptest.data.queue.WorkKeeper
import com.example.kmptest.domain.model.ItemStatus
import com.example.kmptest.domain.model.QueueState
import com.example.kmptest.domain.port.ProcessingQueue
import com.example.kmptest.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Keeps the process alive while videos are being fixed, even with the screen off or the app in the background:
 *  - a foreground service (data-sync type) so the system does not kill the process;
 *  - a partial wake lock so the CPU keeps running with the screen off;
 *  - a progress notification, and a "finished" notification when the queue ends.
 */
class ProcessingService : Service() {
    private val queue: ProcessingQueue by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, progressNotification(queue.state.value), foregroundType())
        acquireWakeLock()
        if (!watching) {
            watching = true
            scope.launch {
                queue.state.collect { state ->
                    if (!state.running) {
                        finish(state)
                    } else {
                        notificationManager().notify(NOTIFICATION_ID, progressNotification(state))
                    }
                }
            }
        }
        // if the process is killed the in-memory queue is gone, so there is nothing to restart
        return START_NOT_STICKY
    }

    /** Android 15 limits this kind of service to ~6 h per day and calls this when the limit is hit. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        queue.cancel()
        stopSelf()
    }

    override fun onTimeout(startId: Int) = onTimeout(startId, 0)

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun finish(state: QueueState) {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (state.items.isNotEmpty() && state.items.none { it.status == ItemStatus.CANCELLED }) notificationManager().notify(DONE_NOTIFICATION_ID, doneNotification(state))
        stopSelf()
    }

    private fun foregroundType(): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KmpTest:processing")
            .apply { acquire(MAX_RUN_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun createChannels() {
        val nm = notificationManager()
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, getString(R.string.notif_done_channel), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun progressNotification(state: QueueState): Notification =
        NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_processing)
            .setContentTitle(getString(R.string.notif_title))
            .setProgress(100, (state.overallFraction * 100).toInt(), state.items.none { it.progress != null })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    private fun doneNotification(state: QueueState): Notification {
        val ok = state.items.count { it.status == ItemStatus.DONE }
        return NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_processing)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("${getString(R.string.all_done)} ($ok/${state.items.size})")
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
    }

    private companion object {
        const val CHANNEL_PROGRESS = "processing"
        const val CHANNEL_DONE = "finished"
        const val NOTIFICATION_ID = 1
        const val DONE_NOTIFICATION_ID = 2
        const val MAX_RUN_MS = 6L * 60 * 60 * 1000
    }
}

class ServiceWorkKeeper(private val context: Context) : WorkKeeper {
    override fun workStarted() {
        ContextCompat.startForegroundService(context, Intent(context, ProcessingService::class.java))
    }
}
