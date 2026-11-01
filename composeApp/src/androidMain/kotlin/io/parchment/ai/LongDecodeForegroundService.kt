package io.parchment.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Lightweight Android Foreground Service that keeps Gemini 4 Argon long-decode streams
 * alive when switching apps or locking the screen on Android 16 (OnePlus 15T).
 */
class LongDecodeForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: ACTION_START
        if (action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val statusText = intent?.getStringExtra(EXTRA_STATUS) ?: "Long decode active…"
        val notification = buildNotification(statusText)

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Parchment Long Decode",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps long-running AI generation streams active in background"
            }
            mgr.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Parchment — Literary AI")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "parchment_long_decode_channel"
        private const val NOTIFICATION_ID = 4101
        const val EXTRA_ACTION = "extra_action"
        const val EXTRA_STATUS = "extra_status"
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
    }
}
