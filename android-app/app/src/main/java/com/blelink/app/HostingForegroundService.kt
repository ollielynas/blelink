package com.blelink.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Exists only to keep this process at foreground priority while BleLink is hosting. All the
 * actual BLE/LAN server logic lives in MainActivity — without a foreground service alongside
 * it, Android (especially aggressive OEM battery managers) can and does kill the whole process
 * once MainActivity is merely backgrounded (e.g. after opening client mode's browser tab),
 * silently taking the BLE GATT server, the LAN HTTP/WebSocket server, and every connected
 * guest down with it. A trivial foreground service in the same process is enough to raise the
 * whole process's priority and avoid that.
 */
class HostingForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "blelink_hosting"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hosting",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.hosting_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
