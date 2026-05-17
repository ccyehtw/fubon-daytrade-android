package com.fubon.daytrade.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 開機廣播接收器
 *
 * 在裝置開機後自動重啟 DeadlineReminderService，確保交易日的背景監控不中斷。
 * 需要 WAKE_LOCK 權限以確保在開機時能正確啟動服務。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, DeadlineReminderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}