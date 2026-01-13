package ru.monjaro.mconfig

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 已弃用：媒体信息现在通过 MediaInfo 类中的 BroadcastReceiver 处理，
 * 这种方式更接近 Mhud 的实现，能更直接地获取车机系统的媒体广播。
 * * 保留此空壳类是为了防止其他引用报错。请确保在 AndroidManifest.xml 中移除此服务的注册，
 * 或者保留注册但不做任何操作。
 */
class MediaNotificationListenerService : NotificationListenerService() {
    companion object {
        fun setMediaInfoCallback(callback: (String, String) -> Unit) {
            // No-op
        }

        fun clearCallback() {
            // No-op
        }

        fun triggerImmediateCheck() {
            // No-op
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("MediaNotification", "Service created (Deprecated mode)")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("MediaNotification", "Listener connected (Deprecated mode)")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // No-op
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op
    }
}