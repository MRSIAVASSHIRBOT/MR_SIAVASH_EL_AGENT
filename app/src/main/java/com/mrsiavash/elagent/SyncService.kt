package com.mrsiavash.elagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

class SyncService : Service() {
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, serviceNotification())
        if (running.compareAndSet(false, true)) {
            worker = Thread({ syncLoop() }, "el-agent-android-sync").also { it.start() }
        }
        return START_STICKY
    }

    private fun syncLoop() {
        var lastHeartbeat = 0L
        while (running.get() && !Thread.currentThread().isInterrupted) {
            try {
                val client = HubClient(this)
                val now = System.currentTimeMillis()
                if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
                    client.heartbeat()
                    lastHeartbeat = now
                }
                if (SecureStore(this).pendingTaskId() == null) {
                    val task = try { client.claimTask() } catch (error: HubHttpException) {
                        if (error.statusCode == 404) null else throw error
                    }
                    if (task != null) notifyTask(task)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Hub sync cycle did not complete: ${error.javaClass.simpleName}")
            }
            try { Thread.sleep(POLL_INTERVAL_MS) } catch (_: InterruptedException) { break }
        }
        running.set(false)
        stopSelf()
    }

    private fun notifyTask(task: org.json.JSONObject) {
        val taskId = task.optString("task_id")
        val alias = task.optString("app_alias")
        if (taskId.isBlank() || alias !in setOf("calculator", "browser", "chatgpt")) return
        SecureStore(this).savePendingTask(taskId, alias)
        val open = Intent(this, TaskConfirmActivity::class.java)
            .putExtra(TaskConfirmActivity.EXTRA_TASK_ID, taskId)
            .putExtra(TaskConfirmActivity.EXTRA_ALIAS, alias)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this, taskId.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("وظیفه جدید از MR_SIAVASH_HUB_AI")
            .setContentText("برای بازکردن «$alias» لمس کنید و اجرا را تأیید کنید.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        getSystemService(NotificationManager::class.java).notify(taskId.hashCode(), notification)
    }

    private fun serviceNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("MR_SIAVASH_EL_AGENT فعال است")
            .setContentText("Heartbeat و دریافت وظایف مجاز Hub")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "همگام‌سازی Agent", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        running.set(false)
        worker?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.mrsiavash.elagent.STOP_SYNC"
        private const val TAG = "ELAgentSync"
        private const val CHANNEL_ID = "el_agent_sync"
        private const val NOTIFICATION_ID = 4100
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val POLL_INTERVAL_MS = 20_000L
    }
}
