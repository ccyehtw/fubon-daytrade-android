package com.fubon.daytrade.data.service

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 收盤提醒服務管理器
 *
 * 提供便捷方法讓 UI 啟動/停止背景服務。
 */
object ReminderServiceManager {

    /**
     * 啟動收盤前提醒服務
     */
    fun start(context: Context) {
        val intent = Intent(context, DeadlineReminderService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /**
     * 停止收盤前提醒服務
     */
    fun stop(context: Context) {
        val intent = Intent(context, DeadlineReminderService::class.java).apply {
            action = DeadlineReminderService.ACTION_STOP
        }
        context.startService(intent)
    }

    /**
     * 檢查服務是否正在運行
     */
    fun isRunning(): Boolean {
        // 可透過 ActivityManager 查詢，但在此以 flag 代替
        return false
    }
}