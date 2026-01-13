package ru.monjaro.mconfig

import android.content.Context
import android.content.Intent
import android.util.Log

class BootService : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        Log.d("BootService", "收到系统广播: $action")

        // 监听开机、热启动及解锁广播
        val validActions = listOf(
            "android.intent.action.BOOT_COMPLETED",
            "android.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            "qwerty" // 自定义触发字
        )

        if (validActions.contains(action)) {
            if (!MConfigStartProc.bootStartService) {
                MConfigStartProc.bootStartService = true

                Log.d("BootService", "正在初始化 MConfig 核心服务...")

                // 1. 启动主配置服务和初始化服务（原逻辑）
                MConfigStartProc.startService(context, false)
                MConfigStartProc.startInitService(context)

                // 2. 额外确保 MediaNotificationListenerService 被拉起
                // 这一步对于通用媒体模式（网易云等）在开机后的稳定性至关重要
                try {
                    val mediaServiceIntent = Intent(context, MediaNotificationListenerService::class.java)
                    context.startService(mediaServiceIntent)
                    Log.d("BootService", "MediaNotificationListenerService 已尝试自启动")
                } catch (e: Exception) {
                    Log.e("BootService", "启动媒体监听服务失败: ${e.message}")
                }
            }
        }
    }
}