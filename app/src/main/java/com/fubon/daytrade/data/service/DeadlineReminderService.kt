package com.fubon.daytrade.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.fubon.daytrade.MainActivity
import com.fubon.daytrade.R
import kotlinx.coroutines.*
import java.util.Calendar

/**
 * 收盤前提醒背景服務
 *
 * 職責：
 * - 前台服務（持續運行於背景）
 * - 監控時間，在 13:00 / 13:20 發送提醒通知
 * - 13:25 自動平倉前 5 分鐘提醒
 *
 * 使用方式：
 *   val intent = Intent(context, DeadlineReminderService::class.java)
 *   context.startForegroundService(intent)
 */
class DeadlineReminderService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitoringJob: Job? = null

    companion object {
        const val CHANNEL_ID = "deadline_reminder_channel"
        const val NOTIFICATION_ID = 1001

        // 提醒時間點（分鐘）
        const val REMINDER_13_00 = 13 * 60       // 13:00 警告
        const val REMINDER_13_20 = 13 * 60 + 20  // 13:20 平倉
        const val REMINDER_13_25 = 13 * 60 + 25  // 13:25 前最後提醒

        const val ACTION_STOP = "com.fubon.daytrade.STOP_REMINDER"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)
        startMonitoring()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        monitoringJob?.cancel()
        serviceScope.cancel()
    }

    // ══════════════════════════════════════════════════════════════
    // 通知渠道
    // ══════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "收盤提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "台股收盤前重要時間點提醒"
                enableVibration(true)
                enableLights(true)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 前台通知
    // ══════════════════════════════════════════════════════════════

    private fun buildForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, DeadlineReminderService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("當日沖銷監控中")
            .setContentText("已連線，監控收盤時間 13:25")
            .setSmallIcon(R.drawable.ic_notification)  // 需要建立 ic_notification drawable
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "停止監控", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ══════════════════════════════════════════════════════════════
    // 時間監控
    // ══════════════════════════════════════════════════════════════

    private fun startMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = serviceScope.launch {
            while (isActive) {
                val now = Calendar.getInstance()
                val dayOfWeek = now.get(Calendar.DAY_OF_WEEK)
                val hour = now.get(Calendar.HOUR_OF_DAY)
                val minute = now.get(Calendar.MINUTE)
                val currentMinutes = hour * 60 + minute

                // 只在工作日（週一=2 到 週五=6）執行
                if (dayOfWeek in Calendar.MONDAY..Calendar.FRIDAY) {
                    when (currentMinutes) {
                        REMINDER_13_00 -> sendDeadlineNotification(
                            title = "⚠️ 13:00 警告",
                            message = "已過 13:00，請留意當日冲銷最後平倉時間 13:25",
                            id = NOTIFICATION_ID + 1
                        )
                        REMINDER_13_20 -> sendDeadlineNotification(
                            title = "🔔 13:20 平倉提醒",
                            message = "距離收盤仅剩 5 分鐘，請確認是否需要平倉",
                            id = NOTIFICATION_ID + 2
                        )
                        REMINDER_13_25 -> sendDeadlineNotification(
                            title = "🚨 收盤最後倒數",
                            message = "13:25 收盤，系統將自動平倉尚未關閉的部位",
                            id = NOTIFICATION_ID + 3
                        )
                    }
                }

                // 每 30 秒檢查一次
                delay(30_000L)
            }
        }
    }

    private fun sendDeadlineNotification(title: String, message: String, id: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(id, notification)
    }
}