package ru.monjaro.mconfig

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class MConfigInitService: Service() {
    private val notificationChannelId = "service_init_notification"
    private var notification: Notification? = null

    private fun notificationCreate(context:Context):Notification{
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?

        notificationManager!!.createNotificationChannel(
            NotificationChannel(
                notificationChannelId,
                getString(R.string.service_init_name),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        val builder: NotificationCompat.Builder = NotificationCompat.Builder(
            context,
            notificationChannelId
        )

        val notifi = builder
            .setSmallIcon(R.drawable.ic_launcher_background)
            .setAutoCancel(true)
            .setContentTitle("MConfig AppsStart Service")
            .setContentText("MConfig AppsStart Service started")
            .setPriority(Notification.PRIORITY_MIN)
            .build()

        return notifi
    }

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()

        if(notification == null) {
            notification = notificationCreate(this)
        }
        startForeground(R.string.service_init_name, notification)
    }

    @SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(notification == null) {
            notification = notificationCreate(this)
            startForeground(R.string.service_init_name, notification)
        }

        // 移除了音量初始化代码
        stopSelf()
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }
}